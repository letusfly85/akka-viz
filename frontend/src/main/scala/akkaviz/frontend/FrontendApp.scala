package akkaviz.frontend

import akkaviz.frontend.ActorRepository.FSMState
import akkaviz.frontend.ApiConnection.Upstream
import akkaviz.frontend.components.{TabMenu, _}
import akkaviz.protocol
import akkaviz.protocol._
import org.scalajs.dom.{console, document}
import rx._

import scala.scalajs.js
import scala.scalajs.js.JSApp
import scalatags.JsDom.all._

case class FsmTransition(fromStateClass: String, toStateClass: String)

object FrontendApp extends JSApp with Persistence with PrettyJson with ManipulationsUI {

  val MaxThroughputLogLen = 100

  private[this] val repo = new ActorRepository()

  private[this] def handleDownstream(messageReceived: (Received) => Unit)(message: protocol.ApiServerMessage): Unit = {

    def addActorLink(sender: String, receiver: String): Unit = {
      val actors = Seq(sender, receiver).filter(FrontendUtil.isUserActor)
      repo.addActorsToSeen(actors)
      if (actors.length > 1) {
        // means both are user actor
        graphView.addLink(sender, receiver)
      }
    }

    message match {
      case ActorSystemCreated(system) =>

      case rcv: Received =>
        messageReceived(rcv)
        addActorLink(rcv.sender, rcv.receiver)

      case ac: AvailableClasses =>
        seenMessages() = seenMessages.now ++ ac.availableClasses

      case Spawned(child) =>
        repo.mutateActor(child) {
          _.copy(isDead = false)
        }

      case fsm: FSMTransition =>
        repo.mutateActor(fsm.ref) {
          state =>
            val transition: (String, String) = (fsm.currentStateClass, fsm.nextStateClass)
            state.copy(fsmState = FSMState(fsm.nextState, fsm.nextData), fsmTransitions = state.fsmTransitions + transition)
        }

      case i: Instantiated =>
        repo.mutateActor(i.ref) {
          _.copy(className = i.clazz)
        }

      case CurrentActorState(ref, state) =>
        repo.mutateActor(ref) {
          _.copy(internalState = state)
        }

      case mb: MailboxStatus =>
        repo.mutateActor(mb.owner) {
          _.copy(mailboxSize = mb.size)
        }

      case Killed(ref) =>
        repo.mutateActor(ref) {
          _.copy(isDead = true)
        }

      case af: ActorFailure =>
        thrownExceptions() = af +: thrownExceptions.now

      case q: Question =>
        asksPanel.receivedQuestion(q)

      case a: Answer =>
        asksPanel.receivedAnswer(a)

      case af: AnswerFailed =>
        asksPanel.receivedAnswerFailed(af)

      case SnapshotAvailable(live, deadActors, rcv) =>
        rcv.foreach {
          case (from, to) => {
            addActorLink(from, to)
          }
        }

        for {
          (ref, clzMaybe) <- live ++ deadActors
          clz <- clzMaybe
        } repo.mutateActor(ref)(_.copy(className = clz))

        deadActors.keys.foreach {
          repo.mutateActor(_) {
            _.copy(isDead = true)
          }
        }

      case ReceiveDelaySet(duration) =>
        delayMillis() = duration.toMillis.toInt

      case ReportingEnabled =>
        monitoringStatus() = On

      case ReportingDisabled =>
        monitoringStatus() = Off

      case Ping                      => {}

      case tm: ThroughputMeasurement => {}

      case Restarted(ref) =>
        repo.mutateActor(ref) {
          _.copy(isDead = false)
        }
    }
  }

  private[this] val upstreamConnection: Upstream = ApiConnection(
    FrontendUtil.webSocketUrl("stream"),
    upstream => {
      upstream.send(SetAllowedMessages(selectedMessages.now))
      upstream.send(ObserveActors(selectedActors.now))
    },
    handleDownstream(messagesPanel.messageReceived)
  ).connect()

  private[this] val monitoringStatus = Var[MonitoringStatus](UnknownYet)
  private[this] val selectedActors = persistedVar[Set[String]](Set(), "selectedActors")
  private[this] val seenMessages = Var[Set[String]](Set())
  private[this] val selectedMessages = persistedVar[Set[String]](Set(), "selectedMessages")
  private[this] val thrownExceptions = Var[Seq[ActorFailure]](Seq())
  private[this] val showUnconnected = Var[Boolean](false)
  private[this] val actorSelector = new ActorSelector(
    repo.seenActors, selectedActors, thrownExceptions, tabManager.openActorDetails
  )
  private[this] val messageFilter = new MessageFilter(seenMessages, selectedMessages, selectedActors)
  private[this] val messagesPanel = new MessagesPanel(selectedActors)
  private[this] val asksPanel = new AsksPanel(selectedActors)
  private[this] val connectionAlert = new Alert()
  private[this] val replTerminal = new ReplTerminal()
  private[this] val graphView = new GraphView(
    showUnconnected, tabManager.openActorDetails, tabManager.openLinkDetails, ActorStateAsNodeRenderer.render
  )
  private[this] val hierarchyPanel = new HierarchyPanel(tabManager.openActorDetails)
  private[this] val settingsTab = new SettingsTab(monitoringStatus, showUnconnected)

  private[this] val topMenu = new TabMenu("top-menu", actorSelector, messageFilter, replTerminal, settingsTab)
  private[this] val bottomMenu = new TabMenu("bottom-menu", messagesPanel, asksPanel, hierarchyPanel)
  private[this] val rightMenu = new TabMenu("right-menu")
  private[this] val tabManager = new TabManager(rightMenu, repo, upstreamConnection, thrownExceptions)

  def main(): Unit = {

    upstreamConnection.status.foreach {
      case ApiConnection.Connecting =>
        connectionAlert.warning("Connecting...")
      case ApiConnection.Connected =>
        connectionAlert.success("Connected!")
        connectionAlert.fadeOut()
      case ApiConnection.Disconnected =>
        connectionAlert.error("Disconnected")
      case ApiConnection.Reconnecting(retry, maxRetries) =>
        connectionAlert.warning(s"Retrying ${retry}/${maxRetries}...")
      case ApiConnection.GaveUp =>
        connectionAlert.error("Gave up, click to reconnect...", _ => upstreamConnection.connect())
    }

    monitoringStatus.triggerLater {
      monitoringStatus.now match {
        case Awaiting(s) =>
          console.log("monitoring status: ", monitoringStatus.now.toString)
          upstreamConnection.send(SetEnabled(s.asBoolean))
        case _ =>
          console.log("monitoring status: ", monitoringStatus.now.toString)
      }
    }

    selectedMessages.triggerLater {
      console.log(s"Will send allowedClasses: ${selectedMessages.now.mkString("[", ",", "]")}")
      upstreamConnection.send(SetAllowedMessages(selectedMessages.now))
    }

    selectedActors.foreach {
      selectedActors =>
        console.log(s"Will send ObserveActors: ${selectedActors.mkString("[", ",", "]")}")
        upstreamConnection.send(ObserveActors(selectedActors))
    }

    delayMillis.triggerLater {
      import scala.concurrent.duration._
      upstreamConnection.send(SetReceiveDelay(delayMillis.now.millis))
    }

    repo.seenActors.triggerLater {
      repo.seenActors.now.foreach {
        actor =>
          graphView.addActor(actor, repo.state(actor))
      }
    }

    repo.newActors.foreach {
      newActors =>
        hierarchyPanel.insert(newActors)
    }

    setupComponents()
  }

  def setupComponents(): Unit = {
    topMenu.attach(document.getElementById("left-panel"))
    bottomMenu.attach(document.getElementById("left-panel"))
    rightMenu.attach(document.getElementById("right-pane"))
    connectionAlert.attach(document.body)
    document.getElementById("globalsettings").appendChild(receiveDelayPanel.render) //FIXME: port to component
    tabManager.attachTab(graphView)
    js.Dynamic.global.$.material.init()
    initResizable()
  }

  private[this] def initResizable(): Unit = {
    val $ = js.Dynamic.global.$
    $("#left-panel").resizable(js.Dictionary("handles" -> "e"))
    $("#top-menu").resizable(js.Dictionary(
      "handles" -> "s",
      "stop" -> (() => $("#top-menu").css("width", ""))
    ))
  }

}