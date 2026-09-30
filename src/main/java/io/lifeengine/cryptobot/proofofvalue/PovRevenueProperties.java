package io.lifeengine.cryptobot.proofofvalue;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * (V7): the fixed, auditable policy {@code pov/revenue-share/v1}. No percentage is computed by anything else.
 *
 * @param contributorShareBps {@code POV_REVENUE_CONTRIBUTOR_SHARE_BPS} (default 2000 = 20 %): the contributor pool, split among the
 *     contributions of the linked ValueEvents pro rata to their units.
 * @param protocolFeeBps {@code POV_REVENUE_PROTOCOL_FEE_BPS} (default 500 = 5 %): only recorded, nothing is transferred.
 * @param treasuryIdentityId {@code POV_TREASURY_IDENTITY_ID} (default {@code cryptobot-001}): the agent whose accounting treasury earns
 *     the revenue and pays the PoV payouts (V8). In the demo every payout is signed from the demo wallet: the treasury is an
 *     accounting view, not a separate wallet per agent yet.
 */
@ConfigurationProperties(prefix = "cryptobot.pov.revenue")
public record PovRevenueProperties(Integer contributorShareBps, Integer protocolFeeBps, String treasuryIdentityId) {

    public static final int DEFAULT_SHARE_BPS = 2000;
    public static final int DEFAULT_FEE_BPS = 500;
    public static final String DEFAULT_TREASURY = "cryptobot-001";

    public PovRevenueProperties {
        contributorShareBps = contributorShareBps == null ? DEFAULT_SHARE_BPS : contributorShareBps;
        protocolFeeBps = protocolFeeBps == null ? DEFAULT_FEE_BPS : protocolFeeBps;
        if (contributorShareBps < 0 || protocolFeeBps < 0 || contributorShareBps + protocolFeeBps > 10_000) {
            throw new IllegalArgumentException("cryptobot.pov.revenue: contributor-share-bps (" + contributorShareBps + ") and protocol-fee-bps ("
                    + protocolFeeBps + ") must be >= 0 and add up to at most 10000");
        }
        treasuryIdentityId = treasuryIdentityId == null || treasuryIdentityId.isBlank() ? DEFAULT_TREASURY : treasuryIdentityId.trim();
    }
}
