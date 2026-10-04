package io.lifeengine.cryptobot.api.quotes;

import io.lifeengine.cryptobot.api.controlplane.ControlPlaneDtos;
import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.application.quotes.ArsQuotesPort;
import io.lifeengine.cryptobot.domain.quotes.QuoteRanking;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * {@code GET /api/cryptobot/quotes/{asset}} — ARS quotes across exchanges and the "recibís X"
 * ranking.
 *
 * <pre>
 * GET /api/cryptobot/quotes/BTC                       board ranked by lowest ask
 * GET /api/cryptobot/quotes/BTC?ars=100000            buy with 100000 ARS → BTC received per exchange
 * GET /api/cryptobot/quotes/USDT?ars=100000&network=TRON   … minus the TRON withdrawal fee when known
 * GET /api/cryptobot/quotes/BTC?side=SELL&amount=0.01 sell 0.01 BTC → ARS received per exchange
 * </pre>
 *
 * Exchanges that failed or do not list the asset come back in {@code unavailable} with a reason;
 * a stale (cached after a failed refresh) quote is flagged {@code stale=true}. 400 on bad input.
 * Same authority as the rest of {@code /api/cryptobot/**}.
 */
@RestController
@RequestMapping(path = "/api/cryptobot/quotes", produces = "application/json")
public class QuotesController {

    private static final Pattern ASSET = Pattern.compile("^[A-Za-z0-9]{2,10}$");
    private static final Pattern NETWORK = Pattern.compile("^[A-Za-z0-9-]{2,16}$");

    private final ArsQuotesPort quotes;

    public QuotesController(ArsQuotesPort quotes) {
        this.quotes = quotes;
    }

    @GetMapping("/{asset}")
    public Mono<QuotesDtos.QuotesResponse> quotes(
            @PathVariable String asset,
            @RequestParam(required = false) BigDecimal ars,
            @RequestParam(required = false) BigDecimal amount,
            @RequestParam(required = false, defaultValue = "BUY") String side,
            @RequestParam(required = false) String network) {
        if (asset == null || !ASSET.matcher(asset).matches()) {
            throw new ControlPlaneExceptions.InvalidRequest("INVALID_ASSET", "asset must match [A-Za-z0-9]{2,10}, e.g. BTC or USDT");
        }
        QuoteRanking.Side s;
        try {
            s = QuoteRanking.Side.valueOf(side.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ControlPlaneExceptions.InvalidRequest("INVALID_SIDE", "side must be BUY or SELL");
        }
        if (network != null && !network.isBlank() && !NETWORK.matcher(network).matches()) {
            throw new ControlPlaneExceptions.InvalidRequest("INVALID_NETWORK", "network must match [A-Za-z0-9-]{2,16}, e.g. BTC, LN, BSC, TRON");
        }
        BigDecimal qty;
        if (s == QuoteRanking.Side.BUY) {
            if (amount != null && ars == null) {
                throw new ControlPlaneExceptions.InvalidRequest("INVALID_AMOUNT", "for side=BUY pass the pesos as ?ars=<monto>");
            }
            qty = ars;
        } else {
            if (ars != null && amount == null) {
                throw new ControlPlaneExceptions.InvalidRequest("INVALID_AMOUNT", "for side=SELL pass the crypto units as ?amount=<unidades>");
            }
            qty = amount;
        }
        if (qty != null && qty.signum() <= 0) {
            throw new ControlPlaneExceptions.InvalidRequest("INVALID_AMOUNT", "amount must be > 0");
        }
        String net = network == null || network.isBlank() ? null : network.trim().toUpperCase(Locale.ROOT);
        return quotes.board(asset).map(board -> QuotesDtos.QuotesResponse.of(board, QuoteRanking.rank(board, s, qty, net), quotes.exchanges()));
    }

    @ExceptionHandler(ControlPlaneExceptions.InvalidRequest.class)
    public ResponseEntity<ControlPlaneDtos.ApiError> invalid(ControlPlaneExceptions.InvalidRequest ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ControlPlaneDtos.ApiError(ex.code(), ex.getMessage()));
    }
}
