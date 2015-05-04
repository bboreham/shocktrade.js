(function () {
    var app = angular.module('shocktrade');

    /**
     * Held Securities Service
     * @author lawrence.daniels@gmail.com
     */
    app.factory('HeldSecurities', function ($rootScope, $http, $log, $q, $timeout, Errors, MySession, ContestService, FavoriteSymbols) {

        var service = {
            symbols: [],
            quotes: []
        };

        service.isHeld = function (symbol) {
            return indexOf(symbol) != -1;
        };

        service.add = function (symbol) {
            var index = indexOf(symbol);
            if (index == -1) {
                // get the user ID
                var id = MySession.getUserID();

                // add the symbol to the list
                service.symbols.unshift(symbol);
                loadQuotes();
            }
        };

        service.remove = function (symbol) {
            var index = indexOf(symbol);
            if (index != -1) {
                // get the user ID
                var id = MySession.getUserID();

                // remove the symbol from the list
                service.symbols.splice(index, 1);
                loadQuotes();
            }
        };

        service.setSymbols = function (symbols) {
            service.symbols = symbols;
            loadQuotes();
        };

        service.getQuotes = function () {
            return service.quotes;
        };

        service.updateQuote = function (quote) {
            for (var n = 0; n < service.quotes.length; n++) {
                if (service.quotes[n].symbol == quote.symbol) {
                    service.quotes[n] = quote;
                    return;
                }
            }
        };

        function indexOf(symbol) {
            for (var n = 0; n < service.symbols.length; n++) {
                if (symbol == service.symbols[n]) {
                    return n;
                }
            }
            return -1;
        }

        function loadQuotes() {
            return $http({
                method: 'POST',
                url: '/api/quotes/list',
                data: angular.toJson(service.symbols)
            }).then(function (response) {
                var quotes = response.data;
                for (var n = 0; n < quotes.length; n++) {
                    quotes[n].favorite = FavoriteSymbols.isFavorite(quotes[n].symbol);
                }
                service.quotes = quotes;
                return service.quotes;
            });
        }

        service.init = function (id) {
            ContestService.getHeldSecurities(id.$oid).then(
                function (response) {
                    $log.info("Loading held securities...");
                    service.setSymbols(response.data);

                    // pre-load the quotes
                    if (service.symbols.length) {
                        loadQuotes().then(function (data) {
                            //console.log("Loaded quotes for " + JSON.stringify(service.symbols));
                            //console.log("Quotes = " + JSON.stringify(data, null , '\t'));
                        });
                    }
                },
                function (response) {
                    Errors.addMessage("Failed to retrieve held securities");
                    console.error(JSON.stringify(response.status, null, '\t'));
                });
        };

        /**
         * Listen for quote updates
         */
        $rootScope.$on("quote_updated", function (event, quote) {
            service.updateQuote(quote);
        });

        return service;
    });

})();