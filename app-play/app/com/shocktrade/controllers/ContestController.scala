package com.shocktrade.controllers

import java.util.Date
import javax.inject.Inject

import akka.util.Timeout
import com.github.ldaniels528.commons.helpers.OptionHelper._
import com.github.ldaniels528.tabular.Tabular
import com.shocktrade.actors.WebSockets
import com.shocktrade.actors.WebSockets.UserProfileUpdated
import com.shocktrade.controllers.ContestControllerForms._
import com.shocktrade.dao.{ContestDAO, SecuritiesDAO, UserProfileDAO}
import com.shocktrade.models.contest.{PlayerRef, _}
import com.shocktrade.models.quote.{MarketQuote, QuoteSnapshot, SectorQuote}
import com.shocktrade.processors.OrderProcessor
import com.shocktrade.util.BSONHelper._
import org.joda.time.DateTime
import play.api._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json.{obj => JS}
import play.api.libs.json._
import play.api.mvc._
import play.modules.reactivemongo.json.BSONFormats._
import play.modules.reactivemongo.{MongoController, ReactiveMongoApi, ReactiveMongoComponents}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}
import scala.util.{Failure, Success, Try}

/**
  * Contest Controller
  * @author lawrence.daniels@gmail.com
  */
class ContestController @Inject()(val reactiveMongoApi: ReactiveMongoApi) extends MongoController with ReactiveMongoComponents with ErrorHandler {
  private val tabular = new Tabular()
  private val DisplayColumns = Seq(
    "name", "creator", "startTime", "expirationTime", "startingBalance", "status",
    "ranked", "playerCount", "levelCap", "perksAllowed", "maxParticipants",
    "participants._id", "participants.name", "participants.facebookID")
  private val contestDAO = ContestDAO(reactiveMongoApi)
  private val securitiesDAO = SecuritiesDAO(reactiveMongoApi)
  private val userProfileDAO = UserProfileDAO(reactiveMongoApi)
  private val orderProcessor = OrderProcessor(reactiveMongoApi)

  implicit val timeout: Timeout = 20.seconds

  ////////////////////////////////////////////////////////////////////////////
  //      API Functions
  ////////////////////////////////////////////////////////////////////////////

  /**
    * Cancels the specified order
    * @param contestId the given contest ID
    * @param playerId  the given player ID
    * @param orderId   the given order ID
    */
  def cancelOrder(contestId: String, playerId: String, orderId: String) = Action.async {
    // pull the order, add it to closedOrders, and return the participant
    contestDAO.closeOrder(contestId.toBSID, playerId.toBSID, orderId.toBSID)
      .map(_.orDie(s"Order $orderId could not be canceled"))
      .map(contest => Ok(Json.toJson(contest)))
      .recover { case e: Exception => Ok(createError(e)) }
  }

  def closeContest(contestId: String) = Action.async {
    val outcome = for {
      contest <- contestDAO.findContestByID(contestId.toBSID) map (_ orDie "Contest not found")
      result <- orderProcessor.closeContest(contest, contest.asOfDate.getOrElse(new Date()))
    } yield result

    outcome map { case (liquidations, contest) =>
      Ok(JS("name" -> contest.name, "count" -> liquidations.size))
    }
  }

  /**
    * Performs a search for contests
    * @return a JSON array of [[Contest]] instances
    */
  def contestSearch = Action.async { implicit request =>
    Try(request.body.asJson map (_.as[ContestSearchForm])) match {
      case Success(Some(form)) =>
        val searchOptions = SearchOptions(
          activeOnly = form.activeOnly,
          available = form.available,
          friendsOnly = form.friendsOnly,
          levelCap = for {allowed <- form.levelCapAllowed; cap <- form.levelCap if allowed} yield cap,
          perksAllowed = form.perksAllowed,
          robotsAllowed = form.robotsAllowed)
        contestDAO.findContests(searchOptions) map (contests => Ok(Json.toJson(contests))) recover {
          case e: Exception => Ok(createError(e))
        }
      case Success(None) =>
        Future.successful(BadRequest("Search options were expected as JSON body"))
      case Failure(e) =>
        Logger.error(s"${e.getMessage}: json = ${request.body.asJson.orNull}")
        Future.successful(InternalServerError(e.getMessage))
    }
  }

  def createChatMessage(contestId: String) = Action.async { implicit request =>
    request.body.asJson map (_.as[MessageForm]) match {
      case Some(form) =>
        val message = Message(sender = form.sender, recipient = form.recipient, text = form.text)
        contestDAO.createMessage(contestId.toBSID, message) map {
          case Some(contest) => Ok(Json.toJson(contest.messages))
          case None => Ok(JsArray())
        } recover {
          case e: Exception =>
            Logger.error(s"${e.getMessage}: json = ${request.body.asJson.orNull}")
            Ok(createError(e))
        }
      case None =>
        Future.successful(Ok(createError("No message sent")))
    }
  }

  /**
    * Creates a new contest
    */
  def createContest = Action.async { implicit request =>
    Try(request.body.asJson.flatMap(_.asOpt[ContestCreateForm])) match {
      case Success(Some(form)) =>
        val outcome = for {
        // deduct the buy-in cost from the profile
          profile <- userProfileDAO.deductFunds(form.playerId.toBSID, form.startingBalance) map (_ orDie "Insufficient funds")
          newContest = makeContest(form)

          // create the contest
          lastError <- contestDAO.createContest(newContest)
        } yield (newContest, lastError)

        outcome map { case (newContest, lastError) =>
          Ok(JS("_id" -> newContest.id))
        } recover {
          case e: Exception =>
            e.printStackTrace()
            Logger.error(s"${e.getMessage}: json = ${request.body.asJson.orNull}")
            Ok(createError(e))
        }
      case Success(None) =>
        Future.successful(BadRequest("Contest form was expected as JSON body"))
      case Failure(e) =>
        Logger.error(s"${e.getMessage}: json = ${request.body.asJson.orNull}")
        Future.successful(InternalServerError(e.getMessage))
    }
  }

  /**
    * Deletes a contest by ID
    * @param contestId the given contest ID
    * @return a contest
    */
  def deleteContestByID(contestId: String) = Action.async {
    val outcome = for {
    // retrieve the contest
      contest <- contestDAO.findContestByID(contestId.toBSID) map (_ orDie "Contest not found")

      // update each participant
      updatedParticipants <- Future.sequence(contest.participants map { participant =>
        userProfileDAO.deductFunds(participant.id, -participant.cashAccount.cashFunds) map (_ orDie "Failed to refund game cash")
      })

      // delete the contest
      lastError <- contestDAO.deleteContestByID(contestId.toBSID)
    } yield lastError

    outcome map { lastError =>
      Ok(if (lastError.inError) JS("error" -> lastError.message) else JS())
    } recover {
      case e: Exception => Ok(createError(e))
    }
  }

  def createMarginAccount(contestId: String, playerId: String) = Action.async {
    contestDAO.createMarginAccount(contestId.toBSID, playerId.toBSID, MarginAccount()) map {
      case Some(contest) => Ok(Json.toJson(contest))
      case None => Ok(JS("error" -> "Game or player not found"))
    } recover {
      case e: Exception => Ok(createError(e))
    }
  }

  def transferFundsBetweenAccounts(contestId: String, playerId: String) = Action.async { implicit request =>
    Try(request.body.asJson.map(_.as[TransferFundsForm])) match {
      case Success(Some(form)) =>
        // perform the atomic update
        contestDAO.transferFundsBetweenAccounts(contestId.toBSID, playerId.toBSID, form.source, form.amount) map {
          case Some(contest) => Ok(Json.toJson(contest))
          case None => Ok(JS("error" -> "Game or player not found"))
        } recover {
          case e: Exception => Ok(createError(e))
        }
      case Success(None) =>
        Logger.error(s"adjustMarginAccountFunds: Bad request -> json = ${request.body.asJson.orNull}")
        Future.successful(BadRequest("JSON body expected"))
      case Failure(e) =>
        Logger.error(s"adjustMarginAccountFunds: Internal server error -> json = ${request.body.asJson.orNull}", e)
        Future.successful(InternalServerError(e.getMessage))
    }
  }

  def getMarginMarketValue(contestId: String, playerId: String) = Action.async {
    val outcome = for {
      contest <- contestDAO.findContestByID(contestId.toBSID) map (_ orDie "Game not found")
      participant = contest.participants.find(_.id == playerId.toBSID) orDie "Player not found"
      positions = participant.positions.filter(_.accountType == AccountTypes.MARGIN)
      marketValue <- computeMarketValue(positions)
    } yield (contest, participant, marketValue)

    outcome map { case (contest, participant, marketValue) =>
      Ok(JS("name" -> contest.name, "_id" -> contest.id, "marginMarketValue" -> marketValue))
    }
  }

  private def computeMarketValue(positions: Seq[Position]): Future[Double] = {
    val symbols = positions.map(_.symbol).distinct
    securitiesDAO.findQuotes[MarketQuote](symbols)(MarketQuote.Fields: _*) map { quotes =>
      val mapping = Map(quotes.map(q => (q.symbol, q)): _*)
      (positions flatMap { pos =>
        for {
          quote <- mapping.get(pos.symbol)
          value <- quote.lastTrade ?? quote.close ?? Option(pos.pricePaid.toDouble)
        } yield value * pos.quantity
      }).sum
    }
  }

  private def makeContest(js: ContestCreateForm) = {
    // create a player instance
    val player = PlayerRef(id = js.playerId.toBSID, name = js.playerName, facebookId = js.facebookId)
    val startTime = if (js.startAutomatically.contains(true)) Some(new Date()) else None

    // create the contest
    Contest(
      name = js.name,
      creator = player,
      creationTime = new Date(),
      startingBalance = js.startingBalance,
      startTime = startTime,
      expirationTime = startTime.map(t => new DateTime(t).plusDays(js.duration).toDate),
      friendsOnly = js.friendsOnly,
      invitationOnly = js.invitationOnly,
      levelCap = (for {allowed <- js.levelCapAllowed; cap <- js.levelCap if allowed} yield cap) map (_.toInt),
      perksAllowed = js.perksAllowed,
      robotsAllowed = js.robotsAllowed,
      messages = List(Message(sender = player, text = s"Welcome to ${js.name}")),
      participants = List(Participant(id = js.playerId.toBSID, js.playerName, js.facebookId, cashAccount = CashAccount(cashFunds = js.startingBalance)))
    )
  }

  /**
    * Creates a new order
    * @param contestId the given contest ID
    * @param playerId  the given player ID
    * @return a [[Contest]] in JSON format
    */
  def createOrder(contestId: String, playerId: String) = Action.async { implicit request =>
    Try(request.body.asJson.map(_.as[OrderForm])) match {
      case Success(Some(form)) =>
        contestDAO.createOrder(contestId.toBSID, playerId.toBSID, makeOrder(playerId, form)) map {
          case Some(contest) => Ok(Json.toJson(contest))
          case None => Ok(createError(s"Contest $contestId not found"))
        } recover {
          case e: Exception => Ok(createError(e))
        }
      case Success(None) =>
        Future.successful(BadRequest("No order information"))
      case Failure(e) =>
        Logger.error(s"Error parsing JSON: json = ${request.body.asJson.orNull}", e)
        Future.successful(BadRequest("Invalid JSON body"))
    }
  }

  private def makeOrder(playerId: String, form: OrderForm) = {
    Order(
      accountType = form.accountType,
      symbol = form.symbol,
      exchange = form.exchange,
      creationTime = if (playerId == "51a308ac50c70a97d375a6b2") new DateTime().minusDays(4).toDate else new Date(), // TODO for testing only
      orderTerm = form.orderTerm,
      orderType = form.orderType,
      price = form.limitPrice,
      priceType = form.priceType,
      quantity = form.quantity,
      commission = Commissions.getCommission(form.priceType, form.perks.getOrElse(Nil)),
      emailNotify = form.emailNotify,
      partialFulfillment = form.partialFulfillment
    )
  }

  def getContestByID(contestId: String) = Action.async {
    contestDAO.findContestByID(contestId.toBSID) map {
      case Some(contest) => Ok(Json.toJson(contest))
      case None => Ok(createError(s"Contest $contestId not found"))
    } recover {
      case e: Exception => Ok(createError(e))
    }
  }

  def getContestRankings(contestId: String) = Action.async {
    (for {
      contest <- contestDAO.findContestByID(contestId.toBSID) map (_ orDie s"Contest $contestId not found")
      rankings <- produceRankings(contest)
    } yield rankings) map (Ok(_)) recover {
      case e: Exception => Ok(createError(e))
    }
  }

  def getContestParticipant(contestId: String, playerId: String) = Action.async {
    (for {
      contest <- contestDAO.findContestByID(contestId.toBSID) map (_ orDie s"Contest $contestId not found")
      player = contest.participants.find(_.id == playerId) orDie s"Player $playerId not found"
      enrichedPlayer <- enrichPositions(player)
    } yield enrichedPlayer).map(p => Ok(JsArray(Seq(p)))) recover {
      case e: Exception => Ok(createError(e))
    }
  }

  /**
    * Returns a trading clock state object
    */
  def getContestsByPlayerID(playerId: String) = Action.async {
    contestDAO.findContestsByPlayerID(playerId.toBSID) map (contests => Ok(Json.toJson(contests)))
  }

  def getEnrichedOrders(contestId: String, playerId: String) = Action.async {
    val outcome = for {
      contest <- contestDAO.findContestByID(contestId.toBSID) map (_ orDie "Contest not found")
      player = contest.participants.find(_.id == playerId.toBSID) orDie "Player not found"
      enriched <- enrichOrders(player)
    } yield enriched \ "orders"

    outcome map {
      case JsDefined(js) => Ok(js)
      case JsUndefined() => InternalServerError("Orders could not be extracted")
    } recover {
      case e: Exception => Ok(createError(e))
    }
  }

  def getEnrichedPositions(contestId: String, playerId: String) = Action.async {
    val outcome = for {
      contest <- contestDAO.findContestByID(contestId.toBSID) map (_ orDie "Contest not found")
      player = contest.participants.find(_.id == playerId.toBSID) orDie "Player not found"
      enriched <- enrichPositions(player)
    } yield enriched \ "positions"

    outcome map {
      case JsDefined(js) => Ok(js)
      case JsUndefined() => InternalServerError("Positions could not be extracted")
    } recover {
      case e: Exception => Ok(createError(e))
    }
  }

  def getHeldSecurities(playerId: String) = Action.async {
    contestDAO.findContestsByPlayerID(playerId.toBSID) map {
      _.flatMap(_.participants.flatMap(_.positions.map(_.symbol)))
    } map (symbols => Ok(JsArray(symbols.distinct.map(JsString)))) recover {
      case e: Exception => Ok(createError(e))
    }
  }

  def getTotalInvestment(playerId: String) = Action.async {
    val outcome = for {
    // calculate the symbol-quantity tuples
      quantities <- contestDAO.findContestsByPlayerID(playerId.toBSID) map (
        _.flatMap(_.participants.flatMap(_.positions.map(p => (p.symbol, p.quantity)))))

      // load the quotes for all order symbols
      symbols = quantities.map(_._1)
      quotes <- securitiesDAO.findQuotes[QuoteSnapshot](symbols)(QuoteSnapshot.Fields: _*)

      // build a mapping of symbol to last trade
      quoteMap = Map(quotes map (q => (q.symbol, q)): _*)

      // compute the total net worth
      netWorth = (quantities flatMap { case (symbol, quantity) => quoteMap.get(symbol).map(_.lastTrade.getOrElse(0.0d) * quantity) }).sum

    } yield netWorth

    outcome map { netWorth =>
      Ok(JS("netWorth" -> netWorth))
    } recover {
      case e: Exception => Ok(createError(e))
    }
  }

  def joinContest(contestId: String) = Action.async { implicit request =>
    Try(request.body.asJson map (_.as[JoinContestForm])) match {
      case Success(Some(js)) =>
        (for {
          startingBalance <- contestDAO.findContestByID(contestId.toBSID) map (_ orDie "Contest not found") map (_.startingBalance)
          participant = Participant(id = js.playerId.toBSID, js.playerName, js.facebookId, CashAccount(cashFunds = startingBalance))
          userProfile <- userProfileDAO.deductFunds(participant.id, startingBalance) map (_ orDie "Insufficient funds")
          contest <- contestDAO.joinContest(contestId.toBSID, participant)
        } yield (userProfile, contest)) map { case (userProfile, contest_?) =>
          WebSockets ! UserProfileUpdated(userProfile)
          Ok(Json.toJson(contest_?))

        } recover {
          case e: Exception => Ok(createError(e))
        }
      case Success(None) => Future.successful(Ok(JS("error" -> "Internal error")))
      case Failure(e) =>
        Logger.error("Contest Join JSON parsing failed", e)
        Future.successful(Ok(createError("Internal error")))
    }
  }

  def quitContest(contestId: String, playerId: String) = Action.async { implicit request =>
    (for {
      c <- contestDAO.findContestByID(contestId.toBSID) map (_ orDie "Contest not found")
      p = c.participants.find(_.id.stringify == playerId) orDie "Player not found"
      u <- userProfileDAO.deductFunds(playerId.toBSID, -p.cashAccount.cashFunds)
      updatedContest <- contestDAO.quitContest(contestId.toBSID, playerId.toBSID)
    } yield (u, updatedContest)) map { case (profile_?, contest_?) =>
      profile_?.foreach(WebSockets ! UserProfileUpdated(_))
      contest_? match {
        case Some(contest) => Ok(Json.toJson(contest))
        case None => Ok(createError("Contest not found"))
      }
    } recover {
      case e: Exception => Ok(createError(e))
    }
  }

  def startContest(contestId: String) = Action.async {
    contestDAO.startContest(contestId.toBSID, startTime = new Date()) map {
      case Some(contest) => Ok(Json.toJson(contest))
      case None => Ok(createError("No qualifying contest found"))
    } recover {
      case e: Exception => Ok(createError(e))
    }
  }

  def updateProcessingHost(contestId: String) = Action.async { implicit request =>
    val host = request.body.asJson flatMap (js => (js \ "host").asOpt[String])
    contestDAO.updateProcessingHost(contestId.toBSID, host) map (_ => Ok(JS()))
  }

  def getAvailablePerks(contestId: String) = Action.async {
    contestDAO.findAvailablePerks(contestId.toBSID) map (perks => Ok(Json.toJson(perks)))
  }

  def getPlayerPerks(id: String, playerId: String) = Action.async {
    // retrieve the participant
    val result = for {
      contest_? <- contestDAO.findContestByID(id.toBSID)
      participant_? = for {
        contest <- contest_?
        participant <- contest.participants.find(_.id.stringify == playerId)
      } yield participant
    } yield participant_?

    result map {
      case Some(participant) =>
        Ok(JS("perkCodes" -> participant.perks, "fundsAvailable" -> participant.cashAccount.cashFunds))
      case None =>
        Ok(JS("error" -> "Perks could not be retrieved"))
    } recover {
      case e =>
        Logger.error("Perks could not be retrieved", e)
        Ok(JS("error" -> "Perks could not be retrieved"))
    }
  }

  /**
    * Facilitates the purchase of perks
    * Returns the updated perks (e.g. ['CREATOR', 'PRCHEMNT'])
    */
  def purchasePerks(contestId: String, playerId: String) = Action.async { request =>
    // get the perks from the request body
    request.body.asJson map (_.as[Seq[String]]) match {
      case Some(perkCodeIDs) =>
        val perkCodes = perkCodeIDs.map(PerkTypes.withName)
        val result = for {
        // retrieve the mapping of perk codes to perk costs
          perkCostsByCode <- contestDAO.findAvailablePerks(contestId.toBSID) map (perks => Map(perks map (p => (p.code, p.cost)): _*))

          // compute the total cost of the perks
          totalCost = (perkCodes flatMap perkCostsByCode.get).sum

          // perform the purchase
          perks_? <- contestDAO.purchasePerks(contestId.toBSID, playerId.toBSID, perkCodes, totalCost)

          // was a margin account purchased?
          margin_? <- {
            if (perkCodes.contains(PerkTypes.MARGIN))
              contestDAO.createMarginAccount(contestId.toBSID, playerId.toBSID, MarginAccount())
            else
              Future.successful(None)
          }

        } yield margin_? ?? perks_?

        result.map {
          case Some(contest) =>
            Ok(Json.toJson(contest))
          case None =>
            Ok(JS("error" -> "Perks could not be purchased"))
        } recover {
          case e => Ok(JS("error" -> "Perks could not be purchased"))
        }
      case _ =>
        Future.successful(BadRequest("JSON array of Perk codes expected"))
    }
  }

  private def produceRankings(contest: Contest): Future[JsArray] = {
    for {
    // compute the total equity for each player
      rankings <- produceNetWorths(contest)

      // sort the participants by net-worth
      rankedPlayers = (1 to rankings.size) zip rankings.sortBy(-_.totalEquity)

    } yield JsArray(rankedPlayers map { case (place, p) => JS("rank" -> placeName(place)) ++ Json.toJson(p).asInstanceOf[JsObject] })
  }

  private def produceNetWorths(contest: Contest): Future[Seq[Ranking]] = {
    // get the contest's values
    val startingBalance = contest.startingBalance
    val participants = contest.participants
    val allSymbols = participants.flatMap(_.positions.map(_.symbol))

    for {
    // query the quotes for all symbols
      quotes <- securitiesDAO.findQuotesBySymbols(allSymbols)
      //_ = tabular.transform(quotes) foreach (s => Logger.info(s))

      // create the mapping of symbols to quotes
      quoteMap = Map(quotes map (q => (q.symbol, q)): _*)

      // get the participants' net worth and P&L
      totalWorths = participants map (asRanking(startingBalance, quoteMap, _))

      _ = tabular.transform(totalWorths) foreach (s => Logger.info(s))

    // return the players' total worth
    } yield totalWorths
  }

  private def enrichOrders(player: Participant): Future[JsObject] = {
    // get the orders and associated symbols
    val symbols = player.orders.map(_.symbol).distinct

    for {
    // load the quotes for all order symbols
      quotes <- securitiesDAO.findQuotes[QuoteSnapshot](symbols)(QuoteSnapshot.Fields: _*)

      // build a mapping of symbol to last trade
      quoteMap = Map(quotes map (q => (q.symbol, q)): _*)

      // enrich the orders
      enrichedOrders = player.orders flatMap { order =>
        for {
          quote <- quoteMap.get(order.symbol)
        } yield Json.toJson(order).asInstanceOf[JsObject] ++ JS(
          "companyName" -> quote.name,
          "lastTrade" -> quote.lastTrade)
      }

    // re-insert into the participant object
    } yield Json.toJson(player).asInstanceOf[JsObject] ++ JS("orders" -> JsArray(enrichedOrders))
  }

  private def enrichPositions(player: Participant): Future[JsObject] = {
    // get the positions and associated symbols
    val symbols = player.positions.map(_.symbol).distinct

    for {
    // load the quotes for all position symbols
      quotes <- securitiesDAO.findQuotes[QuoteSnapshot](symbols)(QuoteSnapshot.Fields: _*)

      // build a mapping of symbol to last trade
      quoteMap = Map(quotes map (q => (q.symbol, q)): _*)

      // enrich the positions
      enrichedPositions = player.positions flatMap { pos =>
        for {
          quote <- quoteMap.get(pos.symbol)
          netValue = quote.lastTrade.map(_ * pos.quantity) getOrElse 0.0d
          gainLoss = netValue - pos.cost
          gainLossPct = 100d * (gainLoss / pos.cost)
        } yield Json.toJson(pos).asInstanceOf[JsObject] ++ JS(
          "companyName" -> quote.name,
          "cost" -> pos.cost,
          "lastTrade" -> quote.lastTrade,
          "netValue" -> netValue,
          "gainLoss" -> gainLoss,
          "gainLossPct" -> gainLossPct)
      }

    // re-insert into the participant object
    } yield Json.toJson(player).asInstanceOf[JsObject] ++ JS("positions" -> JsArray(enrichedPositions))
  }

  private def asRanking(startingBalance: BigDecimal, mapping: Map[String, SectorQuote], p: Participant) = {

    def computeInvestment(positions: Seq[Position]) = {
      positions flatMap { p =>
        for {
          quote <- mapping.get(p.symbol)
          lastTrade <- quote.lastTrade ?? Some(p.pricePaid.toDouble)
        } yield lastTrade * p.quantity
      } sum
    }

    // compute the investment for all positions
    val (cashPositions, marginPositions) = p.positions.partition(_.accountType == AccountTypes.CASH)
    val investment = computeInvestment(cashPositions) + (computeInvestment(marginPositions) * MarginAccount.InitialMargin)

    // add it all up and generate the ranking
    val marginFunds = p.marginAccount.map(_.cashFunds).getOrElse(BigDecimal(0.0d))
    val totalEquity = p.cashAccount.cashFunds + investment + marginFunds
    val gainLoss_% = ((totalEquity - startingBalance) / startingBalance) * 100d
    Ranking(p.id, p.name, p.facebookId, totalEquity, gainLoss_%)
  }

  private def placeName(place: Int) = {
    place match {
      case 1 => "1st"
      case 2 => "2nd"
      case 3 => "3rd"
      case n => s"${n}th"
    }
  }

  implicit def Option2Boolean[T](option: Option[T]): Boolean = option.isDefined

}