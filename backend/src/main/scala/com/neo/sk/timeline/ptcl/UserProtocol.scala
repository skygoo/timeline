package com.neo.sk.timeline.ptcl

import scala.collection.mutable

/**
  * User: sky
  * Date: 2018/4/8
  * Time: 17:00
  */
object UserProtocol {

  case class UserActorInfo(
                            uid: Long,
                            userId: String,
                            bbsId: String,
                            headImg: String,
                            favBoards: mutable.HashSet[(Int, String)]=mutable.HashSet(),
                            favUsers:mutable.HashSet[(Int,Long)]=mutable.HashSet(),
                            favTopic: mutable.HashSet[(Int, String, Long)]=mutable.HashSet(),
                            newFeed: mutable.HashSet[((Int, PostBaseInfo), (Long, AuthorInfo))] = mutable.HashSet(),
                            newReplyFeed: mutable.HashSet[((Int, PostBaseInfo), (Long, AuthorInfo))] = mutable.HashSet()
                          )

  case class AuthorInfo(
                         authorId:Long,
                         authorName:String,
                         origin:Int // 0：水木本身发帖用户 ,1水木plus用户
                       )

  case class PostBaseInfo(
                           origin:Int,
                           boardName:String,
                           postId:Long
                         )

  case class UserFeedReq(
                          post: PostBaseInfo,
                          time: Long
                        )
}
