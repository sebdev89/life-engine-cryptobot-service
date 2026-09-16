package io.lifeengine.cryptobot.domain.intent;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Canonical asset identifier (paper §7, "asset identifiers"). Two grammars, nothing in between:
 *
 * <ul>
 *   <li><b>Symbol</b>: 1 to 12 characters of {@code [A-Za-z0-9._-]}, canonicalized to upper case
 *       ({@code usdc} and {@code USDC} are the same asset, as everywhere else in this service).
 *   <li><b>Mint</b>: a Solana public key, 32 to 44 base58 characters, kept verbatim: base58 is
 *       case-sensitive and changing one letter changes the account.
 * </ul>
 *
 * Anything else (13 to 31 characters, spaces, unicode letters, {@code 0/O/I/l} in a mint) is not
 * an asset and is refused. The value is trimmed and NFC-normalized before matching so that the
 * check itself is canonical.
 */
public record AssetId(String value) {

    private static final Pattern SYMBOL = Pattern.compile("^[A-Za-z0-9._-]{1,12}$");
    private static final Pattern MINT = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]{32,44}$");

    public AssetId {
        if (value == null || (!SYMBOL.matcher(value).matches() && !MINT.matcher(value).matches())) {
            throw new IntentSchemaViolation("asset", "not a symbol nor a base58 mint: " + value);
        }
        if (SYMBOL.matcher(value).matches() && !value.equals(value.toUpperCase(Locale.ROOT))) {
            throw new IntentSchemaViolation("asset", "symbol must be canonical upper case: " + value);
        }
    }

    /** Parses untrusted text: trims, NFC-normalizes, upper-cases a symbol, keeps a mint verbatim. */
    public static AssetId of(String raw) {
        String s = TradingIntent.text("asset", raw);
        if (SYMBOL.matcher(s).matches()) {
            return new AssetId(s.toUpperCase(Locale.ROOT));
        }
        return new AssetId(s);
    }

    public boolean isMint() {
        return MINT.matcher(value).matches() && !SYMBOL.matcher(value).matches();
    }

    @Override
    public String toString() {
        return value;
    }
}
