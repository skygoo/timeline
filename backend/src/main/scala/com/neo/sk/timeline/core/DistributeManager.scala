package com.neo.sk.timeline.core

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import org.slf4j.LoggerFactory
import akka.actor.typed.{ActorRef, Behavior}
import com.neo.sk.timeline.common.Constant.FeedType

import scala.collection.mutable
import com.neo.sk.timeline.ptcl.DistributeProtocol.{DisType, FeedListInfo}
import com.neo.sk.timeline.ptcl.PostProtocol.PostEvent
/**
  * User: sky
  * Date: 2018/4/9
  * Time: 15:16
  */
object DistributeManager {
  val log = LoggerFactory.getLogger(this.getClass)

  sealed trait Command
  final case class NotifyFollowObject(name:String,variety:Int,userId:Long,param:DisType) extends Command with DistributeActor.Command
  final case class QuitFollowObject(name:String, variety:Int, userId:Long) extends Command with DistributeActor.Command
  final case class RemoveFollowObject(name:String,variety:Int) extends Command with DistributeActor.Command
  case class GetFeedList(feedType: Int, name: String, replyTo: ActorRef[FeedListInfo]) extends Command with DistributeActor.Command
  final case class ChildDead(name:String,variety:Int,childRef:ActorRef[DistributeActor.Command]) extends Command
  final case class DealTask(event:PostEvent) extends Command with DistributeActor.Command

  private val objectHash:mutable.HashMap[(String,Int),DisType]=mutable.HashMap() //(name,type)

  val behavior: Behavior[Command] = idle()

  def idle(): Behavior[Command] = {
    Behaviors.immutable[Command] { (ctx, msg) =>
      msg match {
        case msg:NotifyFollowObject=>
          objectHash.put((msg.name,msg.variety),msg.param)
          getDistributeActor(ctx,msg.name,msg.variety,Some(msg.param)) ! msg
          Behaviors.same

        case msg:QuitFollowObject=>
          getDistributeActor(ctx,msg.name,msg.variety,None) ! msg
          Behaviors.same

        case msg:RemoveFollowObject=>
          objectHash.remove(msg.name,msg.variety)
          Behaviors.same

        case DealTask(event)=>
          val follow=List((event.origin+"-"+event.board,FeedType.BOARD),(event.origin+"-"+event.authorId,FeedType.USER),
            (event.origin+"-"+event.board+"-"+event.topicId,FeedType.TOPIC))
          follow.foreach{r=>
            if(objectHash.contains(r._1,r._2)){
              getDistributeActor(ctx,r._1,r._2,None) ! DealTask(event)
            }
          }
          Behaviors.same

        case msg:GetFeedList=>
          if(objectHash.contains(msg.name,msg.feedType)) {
            getDistributeActor(ctx, msg.name, msg.feedType, None) ! msg
          }else{
            log.error("here is a bug------------!!!!!!")
          }
          Behaviors.same

        case msg:ChildDead=>
          log.info(s"${msg.name} is dead")
          objectHash.remove(msg.name,msg.variety)
          Behaviors.same

        case x=>
          log.warn(s"unknown msg: $x")
          Behaviors.unhandled
      }
    }
  }

  private def getDistributeActor(ctx: ActorContext[Command], name:String, variety:Int,param:Option[DisType]) = {
    val childName = s"distributeActor--$name"
    ctx.child(childName).getOrElse {
      val actor=ctx.spawn(DistributeActor.init(name,variety,param), childName)
      ctx.watchWith(actor,ChildDead(childName,variety,actor))
      actor
    }.upcast[DistributeActor.Command]
  }
}
