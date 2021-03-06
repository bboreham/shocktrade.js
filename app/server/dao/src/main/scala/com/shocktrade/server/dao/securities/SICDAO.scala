package com.shocktrade.server.dao.securities

import io.scalajs.npm.mongodb._

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js

/**
  * Standard Industry Code DAO
  * @author Lawrence Daniels <lawrence.daniels@gmail.com>
  */
@js.native
trait SICDAO extends Collection

/**
  * Standard Industry Code DAO Companion
  * @author Lawrence Daniels <lawrence.daniels@gmail.com>
  */
object SICDAO {

  /**
    * Standard Industry Code DAO Extensions
    * @param dao the given [[SICDAO SIC DAO]]
    */
  implicit class SICSDAOExtensions(val dao: SICDAO) {

    /**
      * Retrieves the Standard Industry Code object for the given SIC code
      * @param sicCode the given SIC code
      */
    @inline
    def findByCode(sicCode: Int)(implicit ec: ExecutionContext): Future[Option[SIC]] = {
      dao.findOneFuture[SIC]("sicNumber" $eq sicCode)
    }

  }

  /**
    * Standard Industry Code DAO Extensions
    * @param db the given [[Db database]]
    */
  implicit class SICDAOConstructors(val db: Db) extends AnyVal {

    @inline
    def getSICDAO: SICDAO = {
      db.collection("SIC").asInstanceOf[SICDAO]
    }

  }

}
