package org.irc.scalabot

import java.io.{File, FileWriter}
import java.util.regex.Pattern

import akka.actor.{Actor, ActorSystem, Props}
import com.typesafe.config.{ConfigFactory, ConfigRenderOptions, ConfigValueFactory}
import org.apache.commons.io.IOUtils
import org.pircbotx._
import org.pircbotx.hooks.events.{JoinEvent, MessageEvent}
import org.pircbotx.hooks.types.{GenericChannelUserEvent, GenericMessageEvent}
import org.pircbotx.hooks.{Event, Listener}
import org.slf4j.LoggerFactory

import scala.collection.mutable.{HashSet => MSet}

/**
 * Date: 10/16/14
 */
object Main extends App {

  val system = ActorSystem("irc-actor-system")

  private final val LOG = LoggerFactory.getLogger(Main.getClass)

  val home = new File(System.getProperty("user.home"))

  val configFile = new File(home, ".scalabotrc")

  require(configFile.exists(), s"File $configFile not found")

  require(args.length == 1, "Specify network name")

  val serverAlias = args(0)

  val cfg = ConfigFactory.parseFile(configFile)

  lazy val actor = system.actorOf(Props[BotActor])

  val config = new Configuration.Builder[Nothing]().
    setName(cfg.getString(s"$serverAlias.user.name")).
    setAutoNickChange(true).
    setAutoReconnect(true).
    setLogin(cfg.getString(s"$serverAlias.user.login")).
    setNickservPassword(cfg.getString(s"$serverAlias.user.password")).
    setServer(cfg.getString(s"$serverAlias.host"), cfg.getInt(s"$serverAlias.port")).
    addListener(
      new Listener[Nothing] {
        override def onEvent(event: Event[Nothing]): Unit = actor ! event
      }
    ).
    setSocketFactory(new UtilSSLSocketFactory().trustAllCertificates().disableDiffieHellman()).
    addAutoJoinChannel("#kiev").
    buildConfiguration()

  import scala.collection.JavaConversions._

  val voices = new MSet[String]() ++ cfg.getStringList(s"$serverAlias.voices").toList

  val cmdRegex = Pattern.compile("\\s+")

  val AVOICE_ADD = "!+avoice"
  val AVOICE_REMOVE = "!-avoice"
  val HELP = "!?help"

  class BotActor extends Actor {

    private def processMessage(evt: GenericMessageEvent[_]) = {
      val msg = evt.getMessage
      lazy val users = cmdRegex.split(evt.getMessage).tail

      sealed trait State
      case object Add extends State
      case object Remove extends State

      val updateVoice = if (msg.startsWith(AVOICE_ADD)) {
        voices ++= users
        Some(users, Add)
      } else if (msg.startsWith(AVOICE_REMOVE)) {
        voices --= users
        Some(users, Remove)
      } else if (msg.startsWith(s"$HELP")) {
        evt.respond(s"[ $AVOICE_ADD | $AVOICE_REMOVE ] <nick1> <nick2> ...")
        None
      } else {
        None
      }
      updateVoice foreach {
        case ((delta, state)) ⇒
          val writer = new FileWriter(configFile)
          try {
            writer.write(cfg.withValue(s"$serverAlias.voices", ConfigValueFactory.fromIterable(voices)).root().render(ConfigRenderOptions.defaults()))
            writer.flush()
          } catch {
            case e: Exception ⇒ LOG.error("Can't write config file!", e)
          } finally {
            IOUtils.closeQuietly(writer)
          }
          state match {
            case Add ⇒ evt.respond(s"Added users: ${delta.mkString(",")}")
            case Remove ⇒ evt.respond(s"Removed users: ${delta.mkString(",")}")
          }
      }
    }

    @inline
    private def isAllowed(evt: GenericChannelUserEvent[_]) = {
      val ch = evt.getChannel
      val u = evt.getUser
      ch.isOp(u) || ch.isHalfOp(u) || ch.isSuperOp(u) || ch.isOwner(u)
    }

    override def receive = {
      case evt: JoinEvent[_] ⇒
        if (voices.contains(evt.getUser.getNick)) {
          LOG.info("Voice user {}", evt.getUser)
          evt.getChannel.send().voice(evt.getUser)
        } else {
          LOG.debug("No voice granted: {}", evt.getUser)
        }
      case evt: MessageEvent[_] if isAllowed(evt) ⇒
        processMessage(evt)
      case x ⇒ LOG.trace("Unknown event {}", x)
    }

  }


  LOG.info("Voice enabled for {}", voices)

  val bot = new PircBotX(config)

  bot.startBot()

}