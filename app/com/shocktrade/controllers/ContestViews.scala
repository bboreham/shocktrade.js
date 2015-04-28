package com.shocktrade.controllers

import com.shocktrade.models.contest.Contest
import play.api.mvc.{Action, Controller}

/**
 * Contest Views and JavaScript
 * @author lawrence.daniels@gmail.com
 */
object ContestViews extends Controller {

  def lobby = Action {
    Ok(assets.views.html.playtab.lobby(Contest.MaxPlayers))
  }

  def playCtrl = Action {
    Ok(assets.javascripts.js.playCtrl(Contest.MaxPlayers))
  }

  def playPerksCtrl = Action {
    Ok(assets.javascripts.js.playPerksCtrl())
  }

  def playPortfolioCtrl = Action {
    Ok(assets.javascripts.js.playPortfolioCtrl())
  }

  def playSearchCtrl = Action {
    Ok(assets.javascripts.js.playSearchCtrl())
  }

  def playStatisticsCtrl = Action {
    Ok(assets.javascripts.js.playStatisticsCtrl())
  }

  def search = Action {
    Ok(assets.views.html.playtab.search(Contest.MaxPlayers))
  }

}
