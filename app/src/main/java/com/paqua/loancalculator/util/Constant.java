package com.paqua.loancalculator.util;

/**
 * Common constants
 */
public enum Constant {
    LOAN_OBJECT("LOAN_OBJECT"),
    LOAN_AMORTIZATION_OBJECT("LOAN_AMORTIZATION_OBJECT"),
    USE_SAVED_DATA("USE_SAVED_DATA"),
    GET_LOAN_AMROTIZATION_PAQUA_URL("https://www.paqua.space/api/loan/calculate"),
    DISABLE_ADS_ID("disable_ads_1"),
    SAVE_LOAN_NAME_FORMAT( "%s (%s)");

    public final String value;

    Constant(String value) {
        this.value = value;
    }
}
