package scalafiddle.router

import akka.actor._
import scalafiddle.shared._
import upickle.default._

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

case class RegisterCompiler(id: String, compilerService: ActorRef)

case class UnregisterCompiler(id: String)

case class UpdateState(id: String, newState: CompilerState)

case class CancelCompilation(id: String)

case class CancelCompletion(id: String)

case object RefreshLibraries

case object CheckCompilers

class CompilerManager extends Actor with ActorLogging {
  import CompilerManager._

  val compilers          = mutable.Map.empty[String, CompilerInfo]
  var compilerQueue      = mutable.Queue.empty[(CompilerRequest, ActorRef)]
  val compilationPending = mutable.Map.empty[String, ActorRef]
  var currentLibs        = Seq.empty[ExtLib]
  var compilerTimer      = context.system.scheduler.schedule(5.minute, 1.minute, context.self, CheckCompilers)
  val dependencyRE       = """ *// \$FiddleDependency (.+)""".r

  def now = System.currentTimeMillis()

  override def preStart(): Unit = {
    super.preStart()
    // try to load libraries
    currentLibs = loadLibraries(Config.extLibs, Config.defaultLibs)
    if (currentLibs.isEmpty) {
      // schedule a periodic library update
      context.system.scheduler.scheduleOnce(5.seconds, context.self, RefreshLibraries)
    }
  }

  override def postStop(): Unit = {
    compilerTimer.cancel()
    super.postStop()
  }

  def loadLibraries(uri: String, defaultLibs: Seq[String]): Seq[ExtLib] = {
    try {
      val data = if (uri.startsWith("file:")) {
        // load from file system
        scala.io.Source.fromFile(uri.drop(5), "UTF-8").mkString
      } else if (uri.startsWith("http")) {
        // load from internet
        System.setProperty(
          "http.agent",
          "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/28.0.1500.29 Safari/537.36")
        scala.io.Source.fromURL(uri, "UTF-8").mkString
      } else {
        // load from resources
        scala.io.Source.fromInputStream(getClass.getResourceAsStream(uri), "UTF-8").mkString
      }
      val extLibs = read[Seq[String]](data)
      (extLibs ++ defaultLibs).map(ExtLib(_))
    } catch {
      case e: Throwable =>
        log.error(s"Unable to load libraries")
        Nil
    }
  }

  def extractLibs(source: String): Set[ExtLib] = {
    val codeLines = source.replaceAll("\r", "").split('\n')
    codeLines.collect {
      case dependencyRE(dep) => ExtLib(dep)
    }.toSet
  }

  def selectCompiler(req: CompilerRequest): Option[CompilerInfo] = {
    // extract libs from the source
    val libs = extractLibs(req.source)
    // check that all libs are supported
    libs.foreach(lib =>
      if (!currentLibs.contains(lib)) throw new IllegalArgumentException(s"Library $lib is not supported"))
    // select the best available compiler server based on:
    // 1) time of last activity
    // 2) set of libraries
    compilers.values.toSeq
      .filter(_.state == CompilerState.Ready)
      .sortBy(_.lastActivity)
      .zipWithIndex
      .sortBy(info => if (info._1.lastLibs == libs) -1 else info._2) // use index to ensure stable sort
      .headOption
      .map(_._1.copy(lastLibs = libs))
  }

  def updateCompilerState(id: String, newState: CompilerState) = {
    if (compilers.contains(id)) {
      compilers.update(id, compilers(id).copy(state = newState, lastActivity = now))
    }
  }

  def compilerSeen(id: String) = {
    if (compilers.contains(id)) {
      compilers.update(id, compilers(id).copy(lastSeen = now))
    }
  }

  def processQueue(): Unit = {
    if (compilerQueue.nonEmpty) {
      val (req, sourceActor) = compilerQueue.front
      try {
        selectCompiler(req) match {
          case Some(compilerInfo) =>
            // remove from queue
            compilerQueue.dequeue()
            compilers.update(compilerInfo.id, compilerInfo.copy(state = CompilerState.Compiling))
            compilationPending += compilerInfo.id -> sourceActor
            compilerInfo.compilerService ! req
            // process next in queue
            processQueue()
          case None if compilers.isEmpty =>
            // no compilers registered at this point
            log.error("No compiler instance currently registered")
            sourceActor ! Left("No compiler instance currently registered")
          case None =>
          // no compiler available
        }
      } catch {
        case e: Throwable =>
          sourceActor ! Left(e.getMessage)
      }
    }
  }

  def receive = {
    case RegisterCompiler(id, compilerService) =>
      compilers += id -> CompilerInfo(id, compilerService, CompilerState.Initializing, now, "unknown", Set.empty, now)
      // send current libraries
      compilerService ! UpdateLibraries(currentLibs)
      context.watch(compilerService)

    case UnregisterCompiler(id) =>
      compilers.get(id).foreach(info => context.unwatch(info.compilerService))
      compilers -= id

    case Terminated(compilerService) =>
      // check if it still exist in the map
      compilers.find(_._2.compilerService == compilerService) match {
        case Some((id, info)) =>
          compilers -= id
        case _ =>
      }

    case CompilerPing(id) =>
      compilerSeen(id)

    case UpdateState(id, newState) =>
      updateCompilerState(id, newState)

    case req: CompilerRequest =>
      // add to the queue
      compilerQueue.enqueue((req, sender()))
      processQueue()

    case CancelCompilation(id) =>
      compilerQueue = compilerQueue.filterNot(_._1.id == id)

    case (id: String, CompilerReady) =>
      log.info(s"Compiler $id is now ready")
      updateCompilerState(id, CompilerState.Ready)
      processQueue()

    case (id: String, response: CompilerResponse) =>
      log.debug(s"Received compiler response from $id")
      updateCompilerState(id, CompilerState.Ready)
      compilationPending.get(id) match {
        case Some(actor) =>
          compilationPending -= id
          actor ! Right(response)
        case None =>
          log.error(s"No compilation pending for compiler $id")
      }
      processQueue()

    case RefreshLibraries =>
      try {
        log.debug("Refreshing libraries")
        val newLibs = loadLibraries(Config.extLibs, Config.defaultLibs)
        // are there any changes?
        if (newLibs.nonEmpty && newLibs.toSet != currentLibs.toSet) {
          currentLibs = newLibs
          // inform all connected compilers
          compilers.values.foreach { _.compilerService ! UpdateLibraries(currentLibs) }
          // refresh again
          context.system.scheduler.scheduleOnce(Config.refreshLibraries, context.self, RefreshLibraries)
        } else if (newLibs.isEmpty) {
          // try again soon
          context.system.scheduler.scheduleOnce(5.seconds, context.self, RefreshLibraries)
        }
      } catch {
        case e: Throwable =>
          log.error(s"Error while refreshing libraries", e)
      }

    case CheckCompilers =>
      compilers.foreach {
        case (id, compiler) =>
          if (now - compiler.lastSeen > 120 * 1000) {
            log.error(s"Compiler service $id not seen in ${(now - compiler.lastSeen) / 1000} seconds, terminating compiler")
            context.stop(compiler.compilerService)
          }
      }

    case other =>
      log.error(s"Received unknown message $other")
  }
}

object CompilerManager {
  def props = Props(new CompilerManager)

  case class CompilerInfo(id: String,
                          compilerService: ActorRef,
                          state: CompilerState,
                          lastActivity: Long,
                          lastClient: String,
                          lastLibs: Set[ExtLib],
                          lastSeen: Long)

}
