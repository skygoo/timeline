package com.neo.sk.timeline.core

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer, TimerScheduler}
import org.slf4j.LoggerFactory
import akka.actor.typed.{ActorRef, Behavior}
import com.neo.sk.timeline.models.SlickTables
import com.neo.sk.timeline.models.dao.{FollowDAO, UserDAO}
import com.neo.sk.timeline.ptcl.UserProtocol._
import akka.actor.typed.scaladsl.AskPattern._
import scala.concurrent.duration._
import scala.collection.mutable
import com.neo.sk.timeline.Boot.{distributeManager, executor, scheduler, timeout}
import com.neo.sk.timeline.common.Constant.FeedType
import com.neo.sk.timeline.core.UserManager.UserLogout
import com.neo.sk.timeline.ptcl.DistributeProtocol.FeedListInfo

import scala.concurrent.Future
import scala.util.{Failure, Success}
/**
  * User: sky
  * Date: 2018/4/8
  * Time: 11:11
  */
object UserActor {
  private val log = LoggerFactory.getLogger(this.getClass)

  import com.neo.sk.timeline.core.UserManager._
  trait Command
  case class TimeOut(msg: String) extends Command
  final case class SwitchBehavior(
                                   name: String,
                                   behavior: Behavior[Command],
                                   durationOpt: Option[FiniteDuration] = None,
                                   timeOut: TimeOut = TimeOut("busy time error")
                                 ) extends Command
  final case class RefreshFeed(sortType: Option[Int], pageSize: Option[Int], replyTo: Option[ActorRef[Option[List[UserFeedReq]]]]) extends Command

  final case object CleanFeed extends Command

  private final case object BehaviorChangeKey
  private final case object CleanFeedKey

  private val maxFeedLength = 50
  private val cleanFeedTime = 20.minutes


  def init(uid: Long): Behavior[Command] = {
    Behaviors.setup[Command] { ctx =>
      implicit val stashBuffer = StashBuffer[Command](Int.MaxValue)
      Behaviors.withTimers[Command] { implicit timer =>
        for {
          userInfoOpt <- UserDAO.getUserById(uid)
          feed <- UserDAO.getUserFeed(uid)
          follows <- FollowDAO.getFollows(uid)
        } yield {
          val favBoard = new mutable.HashSet[(Int, String)]()
          val favUser = new mutable.HashSet[(Int,Long)]()
          val favTopic = new mutable.HashSet[(Int, String, Long)]()
          val newFeed = new mutable.HashMap[(Int, PostBaseInfo), (Long,Long)]()
          val newReplyFeed = new mutable.HashMap[(Int, PostBaseInfo), (Long,Long)]()

          if (feed.nonEmpty) {
            feed.filter(_.postTime != 0).sortBy(_.postTime).reverse.take(maxFeedLength).foreach { f =>
              newFeed.put((f.feedType, PostBaseInfo(f.origin, f.boardname, f.topicId,f.postTime)), (f.postId,f.lastReplyTime))
            }
            feed.filter(_.lastReplyTime != 0).sortBy(_.lastReplyTime).reverse.take(maxFeedLength).foreach { f =>
              newReplyFeed.put((f.feedType, PostBaseInfo(f.origin, f.boardname,f.topicId, f.postTime)), (f.postId,f.lastReplyTime))
            }
          }
          follows._1.map(r=>
            favBoard.add(r.origin,r.boardName)
          )
          follows._2.map(r=>
            favUser.add(r.origin,r.followId)
          )
          follows._3.map(r=>
            favTopic.add(r.origin,r.boardName,r.topicId)
          )
          userInfoOpt match {
            case Some(u) =>
              timer.startPeriodicTimer(CleanFeedKey, CleanFeed, cleanFeedTime)
              ctx.self ! RefreshFeed(None, None, None)
              ctx.self ! SwitchBehavior("idle", idle(
                UserActorInfo(uid, u.userId, u.bbsId,u.headImg,
                  favBoard,
                  favUser,
                  favTopic,
                  newFeed,
                  newReplyFeed)))
            case None =>
              log.warn(s"${ctx.self.path} getUserById error when init,error:$uid is not exist")
          }
        }
        switchBehavior(ctx, "busy", busy(), Some(3.minutes), TimeOut("init"))
      }
    }
  }

  def idle(user: UserActorInfo)(implicit stashBuffer: StashBuffer[Command], timer: TimerScheduler[Command]): Behavior[Command] = {
    Behaviors.immutable[Command] { (ctx, msg) =>
      msg match {
        case UserLogout(_,replyTo)=>
          replyTo ! "OK"
          Behaviors.stopped

        case UserFollowBoardMsg(_,boardName,origin)=>
          user.favBoards.add(origin, boardName)
          Behaviors.same

        case msg:GetUserFeed=>
          msg.sortType match {
            case 1 => //根据创建时间
              if (user.newFeed.isEmpty || msg.lastItemTime > user.newFeed.map(_._2._1).max) {
                ctx.self ! RefreshFeed(Some(msg.sortType), Some(msg.pageSize), Some(msg.replyTo))
              } else {
                msg.replyTo ! Some(user.newFeed.filter(_._2._1 < msg.lastItemTime).map(i => UserFeedReq(i._1._2, i._2._1)).toList.sortBy(_.time).reverse.take(msg.pageSize))
              }
            case 2 => //根据最新回复时间
              if (user.newReplyFeed.isEmpty || msg.lastItemTime > user.newReplyFeed.map(_._2._1).max) {
                ctx.self ! RefreshFeed(Some(msg.sortType), Some(msg.pageSize), Some(msg.replyTo))
              } else {
                msg.replyTo ! Some(user.newReplyFeed.filter(_._2._1 < msg.lastItemTime).map(i => UserFeedReq(i._1._2, i._2._1)).toList.sortBy(_.time).reverse.take(msg.pageSize))
              }

            case x@_ =>
              log.debug(s"${ctx.self.path} GetFeed sortType error....sortType is $x")
              msg.replyTo ! None
          }
          Behaviors.same

        case msg:RefreshFeed=>
          val targetList = user.favBoards.map(i => (FeedType.BOARD, i._1 + "-" + i._2)).toList ::: user.favUsers.map(i => (FeedType.USER, i._1 +"-"+i._2)).toList:::user.favTopic.map(i=>(FeedType.TOPIC,i._1+"-"+i._2+"-"+i._3)).toList
          Future.sequence{
            targetList.map{ i =>
              val future: Future[FeedListInfo] = distributeManager ? (DistributeManager.GetFeedList(i._1, i._2, _))
              future.map { data =>
                data.newPosts.foreach { event =>
                  if (!user.newFeed.exists(_._1._2 == PostBaseInfo(event._1, event._2, event._3, event._4))) {
                    user.newFeed.put((i._1, PostBaseInfo(event._1, event._2, event._3, event._4)),
                      (event._5, event._6))
                }
                data.newReplyPosts.foreach { event =>
                  if(!user.newReplyFeed.exists(_._1._2 == PostBaseInfo(event._1, event._2, event._3, event._4))) {
                    user.newReplyFeed.put((i._1, PostBaseInfo(event._1, event._2, event._3,event._4)),
                      (event._5, event._6))
                  }
                }
              }
              }
            }}.onComplete{
            case Success(_) =>
              msg.sortType match {
                case Some(sortType) =>
                  if (sortType == 1)
                    msg.replyTo.get ! Some(user.newFeed.map(i => UserFeedReq(i._1._2, i._2._1)).toList.sortBy(_.time).reverse.take(msg.pageSize.get))
                  else
                    msg.replyTo.get ! Some(user.newReplyFeed.map(i => UserFeedReq(i._1._2, i._2._1)).toList.sortBy(_.time).reverse.take(msg.pageSize.get))
                case None => //
              }
            case Failure(_) =>
              log.debug(s"${ctx.self.path} RefreshFeed fail.....")
          }
          Behaviors.same

        case CleanFeed =>
//          val newFeeds = user.newFeed.map { i =>
//            SlickTables.rUserFeed(-1l, user.uid, i._1._2.origin, i._1._2.boardName, i._1._2.postId, i._2._1,
//              0l, i._2._2.authorId, i._2._2.authorType, i._2._2.nickname, i._1._1)
//          }.toList ::: user.newReplyFeed.map { i =>
//            SlickTables.rUserFeed(-1l, user.uid, i._1._2.origin, i._1._2.boardName, i._1._2.postId, 0l,
//              i._2._1, i._2._2.authorId, i._2._2.authorType, i._2._2.nickname, i._1._1)
//          }.toList
//          UserDAO.cleanFeed(user.uid, newFeeds)
          Behaviors.same

        case x =>
          log.warn(s"unknown msg: $x")
          Behaviors.unhandled
      }
    }
  }

  private def busy()(
    implicit stashBuffer: StashBuffer[Command],
    timer: TimerScheduler[Command]
  ): Behavior[Command] =
    Behaviors.immutable[Command] { (ctx, msg) =>
      msg match {
        case SwitchBehavior(name, behavior, durationOpt, timeOut) =>
          switchBehavior(ctx, name, behavior, durationOpt, timeOut)

        case TimeOut(m) =>
          log.debug(s"${ctx.self.path} is time out when busy,msg=$m")
          Behaviors.stopped

        case unknownMsg =>
          stashBuffer.stash(unknownMsg)
          Behaviors.same

      }
    }

  private[this] def switchBehavior(ctx: ActorContext[Command],
                                   behaviorName: String,
                                   behavior: Behavior[Command],
                                   durationOpt: Option[FiniteDuration] = None,
                                   timeOut: TimeOut = TimeOut("busy time error"))
                                  (implicit stashBuffer: StashBuffer[Command],
                                   timer: TimerScheduler[Command]) = {
    timer.cancel(BehaviorChangeKey)
    durationOpt.foreach(timer.startSingleTimer(BehaviorChangeKey, timeOut, _))
    stashBuffer.unstashAll(ctx, behavior)
  }

}
