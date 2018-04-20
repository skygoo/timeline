package com.neo.sk.timeline.core.postInfo

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer, TimerScheduler}
import com.neo.sk.timeline.core.postInfo.BoardManager.GetTopicInfoReqMsg
import com.neo.sk.timeline.models.SlickTables
import com.neo.sk.timeline.shared.ptcl.PostProtocol.{AuthorInfo, Post}
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.concurrent.duration._
/**
  * User: sky
  * Date: 2018/4/15
  * Time: 15:18
  */
object PostActor {
  private val log = LoggerFactory.getLogger(this.getClass)
  trait Command
  private final case object BehaviorChangeKey
  case class TimeOut(msg:String) extends Command
  final case class SwitchBehavior(
                                   name: String,
                                   behavior: Behavior[Command],
                                   durationOpt: Option[FiniteDuration] = None,
                                   timeOut: TimeOut = TimeOut("busy time error")
                                 ) extends Command
  /**消息*/


  private final val InitTime = Some(5.minutes)

  /**内置转换函数*/
  private def img2ImgList(img:String)={
    img.split(";").toList
  }
  private def post2TopicInfo(origin:Int,t:SlickTables.rPosts,p:SlickTables.rPosts)={
    Post(
      origin,t.boardName,t.boardNameCn,p.postId,p.topicId,t.title,img2ImgList(p.imgs),
      img2ImgList(p.hestiaImgs),Some(p.content),AuthorInfo(p.authorId,p.authorName,p.origin),p.postTime,
      None,isMain = true
    )
  }

  def create(origin:Int,boardName:String,topicId:Long):Behavior[Command] = {
    Behaviors.setup[Command]{
      ctx =>
        log.debug(s"${ctx.self.path} topic=${origin+"-"+boardName+"-"+topicId} is starting...")
        implicit val stashBuffer = StashBuffer[Command](Int.MaxValue)
        Behaviors.withTimers[Command]{ implicit timer =>

          switchBehavior(ctx,"busy",busy(),InitTime,TimeOut("init"))
        }
    }
  }

  private def idle(topicInfo:mutable.HashMap[Long,SlickTables.rPosts]=mutable.HashMap())
                  (
                    implicit stashBuffer:StashBuffer[Command],
                    timer:TimerScheduler[Command]
                  ):Behavior[Command] = {
    Behaviors.immutable[Command]{ (ctx,msg) =>
      msg match {
        case msg:GetTopicInfoReqMsg=>
          val topic=topicInfo(msg.topicId)
          val post=topicInfo(msg.postId)
          msg.replyTo ! BoardManager.GetTopicInfoRsp(post2TopicInfo(msg.origin,topic,post))
          Behaviors.same

        case x=>
          log.warn(s"unknown msg: $x")
          Behaviors.unhandled
      }
    }
  }

  private def busy()(
    implicit stashBuffer:StashBuffer[Command],
    timer:TimerScheduler[Command]
  ): Behavior[Command] =
    Behaviors.immutable[Command] { (ctx, msg) =>
      msg match {
        case SwitchBehavior(name, behavior,durationOpt,timeOut) =>
          switchBehavior(ctx,name,behavior,durationOpt,timeOut)

        case TimeOut(m) =>
          log.debug(s"${ctx.self.path} is time out when busy,msg=${m}")
          Behaviors.stopped

        case unknowMsg =>
          //          log.debug(s"${ctx.self.path} recv a unknow msg when busy:${unknowMsg}")
          stashBuffer.stash(unknowMsg)
          Behavior.same

      }
    }

  private[this] def switchBehavior(ctx: ActorContext[Command],
                                   behaviorName: String, behavior: Behavior[Command], durationOpt: Option[FiniteDuration] = None,timeOut: TimeOut  = TimeOut("busy time error"))
                                  (implicit stashBuffer: StashBuffer[Command],
                                   timer:TimerScheduler[Command]) = {
    //    log.debug(s"${ctx.self.path} becomes $behaviorName behavior.")
    timer.cancel(BehaviorChangeKey)
    durationOpt.foreach(timer.startSingleTimer(BehaviorChangeKey,timeOut,_))
    stashBuffer.unstashAll(ctx,behavior)
  }
}