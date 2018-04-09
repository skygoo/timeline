package com.neo.sk.timeline.models.dao

import com.neo.sk.timeline.utils.DBUtil.db
import slick.jdbc.PostgresProfile.api._
import com.neo.sk.timeline.models.SlickTables._
import com.neo.sk.timeline.models.SlickTables
import com.neo.sk.timeline.common.Constant.{FeedType, UserFollowState}
import com.neo.sk.timeline.Boot.executor
import com.neo.sk.timeline.utils.EhCacheApi
import slick.dbio.DBIOAction
/**
  * User: sky
  * Date: 2018/4/8
  * Time: 10:57
  */
object UserDAO {
  private val userCache = EhCacheApi.createCache[Option[rUser]]("userCache", 1200, 1200)

  def getUserById(id: Long) = {
    userCache.apply(s"userId_$id", () =>
      db.run {
        tUser.filter(_.id === id).result.headOption
      })
  }

  def isUserExist(userId: String) = db.run {
    tUser.filter(r => r.userId === userId).result.headOption
  }

  def addUser(userId: String, time: Long, sessionKey: String, nickName: String, img: String,
              city: String, gender: Int, sha1pwd:String) = db.run {
    tUser.returning(tUser.map(_.id)) +=
      rUser(-1l, userId, "",sha1pwd, time, sessionKey, time, "", "", img, city,
        gender, 0, 1)
  }

  /**
    * feed流
    */

  def getUserFeed(uid: Long) = db.run{
    tUserFeed.filter(_.userId === uid).result
  }

  def cleanFeed(uid: Long, feeds: Seq[rUserFeed]) = {
    val actions = for{
      r1 <- tUserFeed.filter(_.userId === uid).delete
      r2 <- tUserFeed ++= feeds
    } yield {
      r2
    }

    db.run(actions.transactionally)
  }


  def cleanBoardFeed(uid: Long, origin: Int, boardName: String) = db.run{
    tUserFeed.filter(i => i.userId === uid && i.origin === origin && i.boardname === boardName && i.feedType === FeedType.BOARD).delete
  }

  def cleanUserFeed(uid: Long, authorId: String, authorType: Int) = db.run{
    tUserFeed.filter(i => i.userId === uid && i.authorType === authorType && i.authorId === authorId && i.feedType === FeedType.USER).delete
  }

  def cleanTopic(uid: Long, origin: Int, boardName: String, topicId: Long) = db.run{
    tUserFeed.filter(i => i.userId === uid && i.origin === origin && i.boardname === boardName && i.postId === topicId && i.feedType === FeedType.TOPIC).delete
  }
}