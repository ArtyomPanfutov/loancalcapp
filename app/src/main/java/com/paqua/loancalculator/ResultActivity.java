package com.paqua.loancalculator;

import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.res.ResourcesCompat;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.paqua.loancalculator.dto.EarlyPayment;
import com.paqua.loancalculator.dto.EarlyPaymentAdditionalParameters;
import com.paqua.loancalculator.dto.EarlyPaymentRepeatingStrategy;
import com.paqua.loancalculator.dto.EarlyPaymentStrategy;
import com.paqua.loancalculator.dto.Loan;
import com.paqua.loancalculator.dto.LoanAmortization;
import com.paqua.loancalculator.dto.LoanAmortizationRq;
import com.paqua.loancalculator.dto.MonthlyPayment;
import com.paqua.loancalculator.storage.LoanStorage;
import com.paqua.loancalculator.util.CustomDateUtils;
import com.paqua.loancalculator.util.ErrorDialogUtils;
import com.paqua.loancalculator.util.LoanCommonUtils;
import com.paqua.loancalculator.util.OrientationUtils;

import net.time4j.CalendarUnit;
import net.time4j.Duration;
import net.time4j.PlainDate;
import net.time4j.PlainTimestamp;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static com.paqua.loancalculator.util.Constant.GET_LOAN_AMROTIZATION_PAQUA_URL;
import static com.paqua.loancalculator.util.Constant.LOAN_AMORTIZATION_OBJECT;
import static com.paqua.loancalculator.util.Constant.LOAN_OBJECT;
import static com.paqua.loancalculator.util.Constant.SAVE_LOAN_NAME_FORMAT;
import static com.paqua.loancalculator.util.Constant.USE_SAVED_DATA;
import static com.paqua.loancalculator.util.ValidationUtils.hasValidSpinnerItem;
import static com.paqua.loancalculator.util.ValidationUtils.setupRestoringBackgroundOnTextChange;
import static com.paqua.loancalculator.util.ValidationUtils.validateForEmptyText;
import static net.time4j.CalendarUnit.MONTHS;
import static net.time4j.CalendarUnit.YEARS;

public class ResultActivity extends AppCompatActivity {
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("0.00");
    private static final DecimalFormatSymbols SYMBOLS = DECIMAL_FORMAT.getDecimalFormatSymbols();
    private static final int API_REQUEST_TIMEOUT = 10_000;

    static {
        SYMBOLS.setGroupingSeparator(' ');
        DECIMAL_FORMAT.setDecimalFormatSymbols(SYMBOLS);
        DECIMAL_FORMAT.setGroupingUsed(true);
        DECIMAL_FORMAT.setGroupingSize(3);
    }

    private Loan loan;
    private LoanAmortization amortization;

    // This field for storing original overpayment amount (without early payments)
    private BigDecimal overPayment;
    private Boolean useSavedAmortization = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        OrientationUtils.lockOrientationPortrait(this);

        setContentView(R.layout.activity_result_set);

        setVisibilityForAll(INVISIBLE);

        initFromMainActivity();

        initResetAllEarlyPaymentsView();

        initSaveLoanButtonOnClick();
    }

    @Override
    protected void onStart() {
        super.onStart();
        calculateAndInitAmortizationTable();
    }

    /**
     * Calculates if there is no saved loan and initializes amortization table content
     */
    private void calculateAndInitAmortizationTable() {
        TextView loanName = (TextView)findViewById(R.id.loanName);
        if (!useSavedAmortization || amortization == null) {
            tryCalculateLoanAmortization();
            String defaultLoanName = LoanCommonUtils.getDefaultLoanName(getApplicationContext());

            Map<Loan, LoanAmortization> loans = LoanStorage.getAll(getApplicationContext());
            int freeNameCount = findFreeNameCount(loans.keySet(), defaultLoanName);
            if (freeNameCount > 0) {
                loanName.setText(String.format(SAVE_LOAN_NAME_FORMAT.value, defaultLoanName, freeNameCount));
            } else {
                loanName.setText(defaultLoanName);
            }
        } else {
            if (loan != null && loan.getNameWithCount() != null && !loan.getNameWithCount().isEmpty()) {
                loanName.setText(loan.getNameWithCount());
            }
            initAmortizationTableContent();
        }
    }

    /**
     * Saves the loan to the shared preferences
     */
    private void initSaveLoanButtonOnClick() {
        findViewById(R.id.saveLoanButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveButtonOnClickCallback(v);
            }
        });
    }

    /**
     * Callback on save button click
     *
     * Shows confirmation dialog and saves the loan
     *
     * @param view view
     */
    private void saveButtonOnClickCallback(View view) {
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(view.getContext());
        alertDialogBuilder.setPositiveButton(getResources().getString(R.string.save_loan_button_text),
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int arg1) {
                        // TODO will be overridden later to make possible validation
                    }
                });

        alertDialogBuilder.setNegativeButton(getResources().getString(R.string.cancel_extra_payment_button_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
            }
        });

        LayoutInflater inflater = this.getLayoutInflater();
        final View layout = inflater.inflate(R.layout.save_loan_dialog, null);

        final Map<Loan, LoanAmortization> savedLoans = LoanStorage.getAll(getApplicationContext());

        final int nameCount;
        final String displayedName;
        if (loan.getName() == null || loan.getName().isEmpty()) {
            String defaultName = LoanCommonUtils.getDefaultLoanName(getApplicationContext());
            nameCount = findFreeNameCount(savedLoans.keySet(), defaultName);
            displayedName = nameCount > 0 ? String.format(SAVE_LOAN_NAME_FORMAT.value, defaultName, nameCount) : defaultName;
        } else {
            nameCount = findFreeNameCount(savedLoans.keySet(), loan.getName());
            displayedName = nameCount > 0 ? String.format(SAVE_LOAN_NAME_FORMAT.value, loan.getName(), nameCount) : loan.getNameWithCount();
        }

        EditText loanName = layout.findViewById(R.id.loanNameEditText);
        loanName.setText(displayedName);

        alertDialogBuilder.setView(layout);

        final AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();

        // Override on click listener to make possible validation of input fields
        Button positiveButton = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
        positiveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EditText loanName = (EditText) alertDialog.findViewById(R.id.loanNameEditText);

                assert loanName != null;
                if (validateForEmptyText(loanName, alertDialog.getContext().getResources().getColor(R.color.coolRed))) {
                    int newNameCount;
                    if (loanName.getText().toString().equals(displayedName)) {
                        newNameCount = nameCount;
                    } else {
                        newNameCount = savedLoans != null ? findFreeNameCount(savedLoans.keySet(), loanName.getText().toString()) : 0;
                    }

                    String name = newNameCount == 0 ? loanName.getText().toString() : loanName.getText().toString().replace(String.format("(%s)", newNameCount), "").trim();

                    loan = Loan.builder()
                            .uuid(UUID.randomUUID())
                            .name(name)
                            .nameCount(newNameCount)
                            .earlyPayments(loan.getEarlyPayments())
                            .rate(loan.getRate())
                            .amount(loan.getAmount())
                            .term(loan.getTerm())
                            .firstPaymentDate(loan.getFirstPaymentDate())
                            .build();

                    amortization.setOverPaymentAmountWithoutEarlyPayments(overPayment);
                    LoanStorage.put(getApplicationContext(), loan, amortization);
                    Toast.makeText(getApplicationContext(), "Saved!", Toast.LENGTH_LONG).show(); // TODO TEXT

                    TextView nameHeader = (TextView) findViewById(R.id.loanName);
                    nameHeader.setText(loan.getNameWithCount());

                    alertDialog.dismiss();
                }
            }
        });

    }

    /**
     * Finds free loan name count
     *
     * @param loans loans
     * @param defaultLoanName name for search
     *
     * @return free name count number
     */
    private int findFreeNameCount(Set<Loan> loans, String defaultLoanName) {
        int i = 0;
        while (existsLoanWithName(loans, defaultLoanName, i)) {
            i++;
        }

        return i;
    }

    /**
     * Checks name for existing on loans set
     *
     * @param loans
     * @param name
     *
     * @return check result
     */
    private boolean existsLoanWithName(Set<Loan> loans, String name, Integer count) {
        for (Loan loan : loans) {
            if (loan.getName().equals(name) && loan.getNameCount().equals(count)) {
                return true;
            }
        }
        return false;
    }


    /**
     * Rebuilds table layout on resume unless it will be collapsed :(
     */
    @Override
    protected void onResume() {
        super.onResume();

        if (loan != null && amortization != null) {
            rebuildAmortizationTable();
        }
    }

    /**
     * Initializes paint flags and on-click callback for the reset view
     */
    private void initResetAllEarlyPaymentsView() {
        TextView resetEarlyPayments = (TextView) findViewById(R.id.resetAllEarlyPayments);
        resetEarlyPayments.setPaintFlags(resetEarlyPayments.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        resetEarlyPayments.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetAllEarlyPayments();
                setOverpaymentAmountVisibility();
            }
        });
    }

    /**
     * Initializes loan from previous screen
     */
    private void initFromMainActivity() {
        Intent intent = getIntent();
        loan = (Loan) intent.getExtras().get(LOAN_OBJECT.value);

        useSavedAmortization = intent.getExtras().getBoolean(USE_SAVED_DATA.value);

        if (useSavedAmortization) {
            amortization = (LoanAmortization) intent.getExtras().get(LOAN_AMORTIZATION_OBJECT.value);
            overPayment = amortization.getOverPaymentAmountWithoutEarlyPayments();
        }
    }

    /**
     * Wrapper for {@link this#calculateLoanAmortization()}
     */
    private void tryCalculateLoanAmortization() {
        try {
            calculateLoanAmortization();
        } catch (Exception e) {
            ErrorDialogUtils.showSomethingWentWrongDialog(ResultActivity.this);
            e.printStackTrace(); // TODO
        }
    }

    /**
     * Makes REST API request and builds the amortization table
     * @throws JSONException
     */
    private void calculateLoanAmortization() throws JSONException {
        findViewById(R.id.progressBar).setVisibility(VISIBLE);
        RequestQueue queue = Volley.newRequestQueue(ResultActivity.this);

        LoanAmortizationRq lastLoanRequestParam = new LoanAmortizationRq(loan);

        final JSONObject requestParams = new JSONObject(
                new GsonBuilder().enableComplexMapKeySerialization()
                        .create()
                        .toJson(lastLoanRequestParam));

        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest
                (Request.Method.POST, GET_LOAN_AMROTIZATION_PAQUA_URL.value, requestParams, new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject response) {
                        calculateLoanAmortizationCallback(response);
                    }
                }, new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        findViewById(R.id.progressBar).setVisibility(GONE);
                        ErrorDialogUtils.showSomethingWentWrongDialog(ResultActivity.this);
                        System.out.println("Something went wrong :( " + error);
                    }
                }) {
        };

        jsonObjectRequest.setRetryPolicy(new DefaultRetryPolicy(
                API_REQUEST_TIMEOUT,
                3,
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        ));

        queue.add(jsonObjectRequest);
    }

    /**
     * Callback on amortization data request
     * Sets values on views
     *
     * @param response
     */
    private void calculateLoanAmortizationCallback(JSONObject response) {
        findViewById(R.id.progressBar).setVisibility(GONE);
        Gson gson = new Gson();
        amortization = gson.fromJson(response.toString(), LoanAmortization.class);
        System.out.println(amortization);

        loan = Loan.builder()
                .uuid(loan.getUuid())
                .name(loan.getName())
                .nameCount(loan.getNameCount())
                .amount(loan.getAmount())
                .term(loan.getTerm())
                .rate(loan.getRate())
                .earlyPayments(amortization.getEarlyPayments())
                .firstPaymentDate(loan.getFirstPaymentDate())
                .build();

        initAmortizationTableContent();
    }

    /**
     * Initializes and fills amortization content on layout
     */
    private void initAmortizationTableContent() {
        fillHeaderValues();

        rebuildAmortizationTable();

        setVisibilityForAll(VISIBLE);
        setOverpaymentAmountVisibility();

        if (loan.getFirstPaymentDate() == null || loan.getFirstPaymentDate().length() == 0) {
            setAlreadyPaidInfoInvisible();
        } else {
            Date current = CustomDateUtils.getCurrentDateWithoutTime();
            Date firstPaymentDate;
            try {
               firstPaymentDate = CustomDateUtils.getDateFromApiString(loan.getFirstPaymentDate());
            } catch (Exception e) {
                e.printStackTrace();
                firstPaymentDate = current;
            }
            if (!firstPaymentDate.before(current)) {
                setAlreadyPaidInfoInvisible();
            }
        }
    }

    private void setAlreadyPaidInfoInvisible() {
        findViewById(R.id.alreadyPaidHeader).setVisibility(INVISIBLE);
        findViewById(R.id.principalPaidAmount).setVisibility(INVISIBLE);
        findViewById(R.id.interestPaidAmount).setVisibility(INVISIBLE);
        findViewById(R.id.principalPaidHeader).setVisibility(INVISIBLE);
        findViewById(R.id.interestPaidHeader).setVisibility(INVISIBLE);
        findViewById(R.id.alreadyPaidTerm).setVisibility(INVISIBLE);

        // Change layout constraint
        View scrollView = findViewById(R.id.scrollView2);
        ConstraintLayout.LayoutParams layoutParams = (ConstraintLayout.LayoutParams) scrollView.getLayoutParams();
        layoutParams.topToBottom = R.id.resetAllEarlyPayments;
        scrollView.setLayoutParams(layoutParams);
    }

    /**
     * Sets visibility according to value
     */
    private void setOverpaymentAmountVisibility() {
        TextView overPaymentAmountWithEarly = (TextView) findViewById(R.id.overPaymentWithEarly);
        TextView reset = (TextView) findViewById(R.id.resetAllEarlyPayments);
        TextView hintText = (TextView) findViewById(R.id.hintToEarlyPayment);

        if (overPaymentAmountWithEarly.getText() == null || overPaymentAmountWithEarly.getText().length() == 0) {
            overPaymentAmountWithEarly.setVisibility(INVISIBLE);
            reset.setVisibility(INVISIBLE);
            hintText.setVisibility(VISIBLE);
        } else {
            overPaymentAmountWithEarly.setVisibility(VISIBLE);
            reset.setVisibility(VISIBLE);
            hintText.setVisibility(INVISIBLE);
        }
    }


    /**
     * Fills headers with values from request
     */
    private void fillHeaderValues() {
        TextView monthlyPaymentAmount = (TextView) findViewById(R.id.monthlyPaymentValue);

        monthlyPaymentAmount.setText(
                DECIMAL_FORMAT.format(amortization
                            .getMonthlyPaymentAmount()
                            .setScale(2, RoundingMode.HALF_UP))
        );

        TextView overPaymentAmount = (TextView) findViewById(R.id.overPaymentAmount);
        if (amortization.getEarlyPayments() != null && amortization.getEarlyPayments().entrySet().size() > 0) {
            TextView overPaymentAmountWithEarly = (TextView) findViewById(R.id.overPaymentWithEarly);
            overPaymentAmountWithEarly.setText(DECIMAL_FORMAT.format(amortization.getOverPaymentAmount()));

            findViewById(R.id.hintToEarlyPayment).setVisibility(INVISIBLE);
        }

        // Save the overpayment amount calculated in the first request - will use it to overpayment header that does not include early payments
        if (overPayment == null) {
            overPayment = amortization.getOverPaymentAmount();
            setOverpaymentAmountVisibility();
        }

        if (overPayment != null) {
            overPaymentAmount.setText(DECIMAL_FORMAT.format(overPayment));
        }

        // Already paid amounts
        if (loan.getFirstPaymentDate() != null && loan.getFirstPaymentDate().length() != 0) {
            fillAlreadyPaidAmount();
        } else {
            setAlreadyPaidInfoInvisible();
        }

    }

    @Deprecated /* needs to be refactored */
    private void fillAlreadyPaidAmount() {
        Date currentDate = CustomDateUtils.getCurrentDateWithoutTime();

        BigDecimal alreadyPaidPrincipal = BigDecimal.ZERO;
        BigDecimal alreadyPaidInterest = BigDecimal.ZERO;

        for (MonthlyPayment monthlyPayment : amortization.getMonthlyPayments()) {
            Date paymentDate;
            try {
                 paymentDate = CustomDateUtils.getDateFromApiString(monthlyPayment.getPaymentDate());
            } catch (Exception e) {
                System.out.println("Error while parsing date " + monthlyPayment.getPaymentDate());
                continue;
            }

            if (paymentDate.before(currentDate)) {
                alreadyPaidPrincipal = alreadyPaidPrincipal.add(monthlyPayment.getDebtPaymentAmount());
                alreadyPaidInterest = alreadyPaidInterest.add(monthlyPayment.getInterestPaymentAmount());
            } else {
                break;
            }
        }

        TextView interest = findViewById(R.id.interestPaidAmount);
        TextView principal = findViewById(R.id.principalPaidAmount);

        principal.setText(DECIMAL_FORMAT.format(alreadyPaidPrincipal));
        interest.setText(DECIMAL_FORMAT.format(alreadyPaidInterest));

        Date firstPaymentDate;
        try {
            firstPaymentDate = CustomDateUtils.getDateFromApiString(loan.getFirstPaymentDate());
        } catch (ParseException e) {
            firstPaymentDate = currentDate;
            e.printStackTrace();
        }

        Calendar firstPaymentDateCalendar = Calendar.getInstance(TimeZone.getDefault());
        firstPaymentDateCalendar.setTime(firstPaymentDate);

        Calendar currentDateCalendar = Calendar.getInstance(TimeZone.getDefault());
        currentDateCalendar.setTime(currentDate);

        PlainTimestamp start = PlainDate.of(
                firstPaymentDateCalendar.get(Calendar.YEAR),
                firstPaymentDateCalendar.get(Calendar.MONTH) + 1,
                firstPaymentDateCalendar.get(Calendar.DAY_OF_MONTH)
        ).atTime(0, 0);

        PlainTimestamp end = PlainDate.of(
                currentDateCalendar.get(Calendar.YEAR),
                currentDateCalendar.get(Calendar.MONTH) + 1,
                currentDateCalendar.get(Calendar.DAY_OF_MONTH)
        ).atTime(0, 0);

        Duration<CalendarUnit> duration = start.until(end, Duration.in(YEARS, MONTHS));

        long years = duration.getPartialAmount(YEARS);
        long months = duration.getPartialAmount(MONTHS);

        TextView alreadyPaidTerm = findViewById(R.id.alreadyPaidTerm);

        alreadyPaidTerm.setText(LoanCommonUtils.getTermString(years, months, getApplicationContext()));

        if (alreadyPaidInterest.compareTo(BigDecimal.ZERO) <= 0 || alreadyPaidPrincipal.compareTo(BigDecimal.ZERO) <= 0) {
            setAlreadyPaidInfoInvisible();
        }
    }

    /**
     * Builds amortization table content
     */
    private void rebuildAmortizationTable() {
        TableLayout tableLayout = (TableLayout)findViewById(R.id.amortizationTable);
        tableLayout.removeAllViews();

        buildAmortizationTableHeader(tableLayout);

        buildAmortizationTableContent(tableLayout);
    }

    /**
     * Condition for showing payment date to user
     */
    private boolean showPaymentDate() {
        return loan.getFirstPaymentDate() != null && !loan.getFirstPaymentDate().isEmpty();
    }

    /**
     * Determines values for an early payment cell
     *
     * @param payment
     * @return Value for an early payment cell
     */
    private String getEarlyPaymentValue(MonthlyPayment payment) {
        String earlyPaymentValue;
        if (payment.getAdditionalPaymentAmount() != null && payment.getAdditionalPaymentAmount().compareTo(BigDecimal.ZERO) > 0) {
            EarlyPayment earlyPayment = amortization.getEarlyPayments().get(payment.getMonthNumber());
            String earlyPaymentBrief = earlyPayment.getStrategy() == EarlyPaymentStrategy.DECREASE_TERM ? getResources().getString(R.string.reduce_term_brief) : getResources().getString(R.string.reduce_payment_amount_brief);
            earlyPaymentValue = "+ " + DECIMAL_FORMAT.format(payment.getAdditionalPaymentAmount()) + " (" + earlyPaymentBrief + ")";
        } else {
            earlyPaymentValue = "+ " + getResources().getString(R.string.add_extra_payment_text);
        }

        return earlyPaymentValue;
    }

    /**
     * Handler on early payment button click
     *
     * Shows early payment dialog
     */
    private void earlyPaymentAddOnClickCallback(View v) {
        final TextView textView = (TextView) v;
        final Integer paymentNumber = (Integer) ((TableRow) v.getParent()).getTag();

        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setPositiveButton(getResources().getString(R.string.add_extra_payment_button_text), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                earlyPaymentAddOnOKCallback((AlertDialog) dialog, paymentNumber); // TODO Probably don't need this - it will be overridden anyway
            }
        });
        builder.setNegativeButton(getResources().getString(R.string.cancel_extra_payment_button_text), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
            }
        });
        builder.setNeutralButton(getResources().getString(R.string.reset_extra_payment_button_text), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                resetEarlyPayment(paymentNumber);
            }
        });
        LayoutInflater inflater = this.getLayoutInflater();
        final View layout = inflater.inflate(R.layout.early_payment, null);

        builder.setView(layout);

        setVisibilityForRepeatingStrategy(layout, INVISIBLE);
        layout.findViewById(R.id.toCertainMonthSpinner).setVisibility(INVISIBLE);

        layout.findViewById(R.id.untilSpecificMonthRadioButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                untilSpecificMonthRadioButtonOnClickCallback(v, layout, paymentNumber);
            }
        });
        layout.findViewById(R.id.untilEndTermRadioButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                layout.findViewById(R.id.toCertainMonthSpinner).setVisibility(INVISIBLE);
            }
        });
        layout.findViewById(R.id.repeatInNextMonthSwitch).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Switch repeatingSwitch = (Switch) v;

                if (repeatingSwitch.isChecked()) {
                    setVisibilityForRepeatingStrategy(layout, VISIBLE);
                } else {
                    setVisibilityForRepeatingStrategy(layout, INVISIBLE);
                    layout.findViewById(R.id.toCertainMonthSpinner).setVisibility(INVISIBLE);
                }
            }
        });

        if (paymentNumber != null) {
            TextView forEarlyPaymentNumber = (TextView) layout.findViewById(R.id.forEarlyPaymentNumber);

            forEarlyPaymentNumber.setText(String.format(getResources().getString(R.string.for_payment_number_text) + "%s", paymentNumber + 1));
        }

        if (paymentNumber != null && loan.getEarlyPayments() != null) {
            EditText earlyPaymentAmountView = (EditText) layout.findViewById(R.id.earlyPaymentAmount);

            if (loan.getEarlyPayments().get(paymentNumber) != null) {
                earlyPaymentAmountView.setText(loan.getEarlyPayments().get(paymentNumber).getAmount().toString());
            }
        }

        final AlertDialog dialog = builder.create();
        dialog.show();

        // Override on click listener to make possible validation of input fields
        Button positiveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        positiveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                earlyPaymentAddOnOKCallback(dialog, paymentNumber);
            }
        });
    }

    /**
     * Implements callback for radio button {untilSpecificMonthRadioButton}
     *
     * Sets visibility for spinner and fills it content
     *
     * @param v
     * @param layout
     * @param paymentNumber
     */
    private void untilSpecificMonthRadioButtonOnClickCallback(View v, View layout, Integer paymentNumber) {
        Spinner toCertainMonth = (Spinner)layout.findViewById(R.id.toCertainMonthSpinner);
        toCertainMonth.setVisibility(VISIBLE);

        List<String> items = new ArrayList<>();
        items.add(getResources().getString(R.string.choose_month));

        for (int i = paymentNumber + 1; i < loan.getTerm(); i++) {
            items.add(String.valueOf(i + 1));
        }

        ArrayAdapter<String> spinnerArrayAdapter = new ArrayAdapter<>(v.getContext(), android.R.layout.simple_spinner_dropdown_item, items);
        toCertainMonth.setAdapter(spinnerArrayAdapter);
    }

    /**
     * Sets visibility for a repeating strategy view group
     * @param visibility
     */
    private void setVisibilityForRepeatingStrategy(View layout, int visibility) {
        View repeatingStrategy = layout.findViewById(R.id.repeatingStrategyRadioGroup);
        repeatingStrategy.setVisibility(visibility);
    }

    /**
     * Deletes one early payment
     * @param paymentNumber
     */
    private void resetEarlyPayment(Integer paymentNumber) {
        Map<Integer, EarlyPayment> earlyPayment = new HashMap<>(amortization.getEarlyPayments());
        earlyPayment.remove(paymentNumber);

        if (earlyPayment.isEmpty()) {
            resetAllEarlyPayments();
        } else {
            loan = Loan.builder()
                    .uuid(loan.getUuid())
                    .name(loan.getName())
                    .nameCount(loan.getNameCount())
                    .amount(loan.getAmount())
                    .term(loan.getTerm())
                    .rate(loan.getRate())
                    .earlyPayments(earlyPayment)
                    .firstPaymentDate(loan.getFirstPaymentDate())
                    .build();

            tryCalculateLoanAmortization();
        }
    }

    /**
     * Resets all early payments and calculates amortization
     */
    private void resetAllEarlyPayments() {
        loan = Loan.builder()
                .uuid(loan.getUuid())
                .name(loan.getName())
                .nameCount(loan.getNameCount())
                .amount(loan.getAmount())
                .term(loan.getTerm())
                .rate(loan.getRate())
                .earlyPayments(new HashMap<Integer, EarlyPayment>())
                .firstPaymentDate(loan.getFirstPaymentDate())
                .build();

        amortization = null;
        overPayment = null;

        TextView overPayment = findViewById(R.id.overPaymentWithEarly);
        overPayment.setText("");

        tryCalculateLoanAmortization();
    }

    /**
     * Handler on early payment add confirm
     * Makes an API call and rebuilds the result table
     *
     * @param paymentNumber Number of the payment in the schedule
     */
    private void earlyPaymentAddOnOKCallback(AlertDialog dialog, Integer paymentNumber) {
        EditText earlyPaymentAmountView = (EditText) dialog.findViewById(R.id.earlyPaymentAmount);
        Spinner monthSpinner = (Spinner) dialog.findViewById(R.id.toCertainMonthSpinner);

        setupRestoringBackgroundOnTextChange(earlyPaymentAmountView);
        setupRestoringBackgroundOnTextChange(monthSpinner);
        boolean isValid = validateForEmptyText(earlyPaymentAmountView, getResources().getColor(R.color.coolRed));

        RadioButton untilCertainMonth = (RadioButton) dialog.findViewById(R.id.untilSpecificMonthRadioButton);
        if (untilCertainMonth.isChecked()) {
            if (isValid) isValid = hasValidSpinnerItem(monthSpinner, getResources().getColor(R.color.coolRed));
        }

        if (isValid) {
            BigDecimal amount = new BigDecimal(earlyPaymentAmountView.getText().toString());

            Switch repeatingSwitch = (Switch) dialog.findViewById(R.id.repeatInNextMonthSwitch);

            EarlyPaymentRepeatingStrategy repeatingStrategy = EarlyPaymentRepeatingStrategy.SINGLE;
            if (repeatingSwitch.isChecked()) {
                repeatingStrategy = ((RadioButton) dialog.findViewById(R.id.untilSpecificMonthRadioButton)).isChecked() ? EarlyPaymentRepeatingStrategy.TO_CERTAIN_MONTH : EarlyPaymentRepeatingStrategy.TO_END;
            }

            EarlyPaymentStrategy earlyPaymentStrategy = ((RadioButton) dialog.findViewById(R.id.termDecrease)).isChecked() ? EarlyPaymentStrategy.DECREASE_TERM : EarlyPaymentStrategy.DECREASE_MONTHLY_PAYMENT;

            Map<Integer, EarlyPayment> earlyPayments = loan.getEarlyPayments() != null ? loan.getEarlyPayments() : new HashMap<Integer, EarlyPayment>();

            Map<EarlyPaymentAdditionalParameters, String> parameters = new HashMap<>();
            if (repeatingStrategy == EarlyPaymentRepeatingStrategy.TO_CERTAIN_MONTH) {
                Spinner monthNumber = (Spinner) dialog.findViewById(R.id.toCertainMonthSpinner);
                int month = Integer.parseInt(monthNumber.getSelectedItem().toString());

                parameters.put(EarlyPaymentAdditionalParameters.REPEAT_TO_MONTH_NUMBER, String.valueOf(month));
            }

            earlyPayments.put(paymentNumber, new EarlyPayment(
                    amount,
                    earlyPaymentStrategy,
                    repeatingStrategy,
                    parameters
            ));

            // Construct new loan because it is immutable
            loan = Loan.builder()
                    .uuid(loan.getUuid())
                    .name(loan.getName())
                    .nameCount(loan.getNameCount())
                    .amount(loan.getAmount())
                    .rate(loan.getRate())
                    .term(loan.getTerm())
                    .earlyPayments(earlyPayments)
                    .firstPaymentDate(loan.getFirstPaymentDate())
                    .build();

            tryCalculateLoanAmortization();

            dialog.dismiss();
        }
    }

    /**
     * Builds table header
     * @param tl
     */
    private void buildAmortizationTableHeader(TableLayout tl) {
        // Header
        TableRow header = new TableRow(ResultActivity.this);
        Drawable background = getResources().getDrawable(R.drawable.amortization_header_background);
        int headerFontSize = 15;
        int textColor = getResources().getColor(R.color.coolDarkColor);

        Typeface standardTypeface = getStandardTypeface();

        header.setGravity(Gravity.RIGHT | Gravity.TOP);
        header.setBackground(background);
        header.setPadding(0, 50, 10, 50);

        TextView paymentNumberColumn = new TextView(ResultActivity.this);
        paymentNumberColumn.setText("№   ");
        paymentNumberColumn.setTextSize(headerFontSize);
        paymentNumberColumn.setTextColor(textColor);
        paymentNumberColumn.setTypeface(standardTypeface);
        paymentNumberColumn.setPadding(10, 0, 10 , 0);

        TextView dateColumn = new TextView(ResultActivity.this);
        dateColumn.setText(getResources().getString(R.string.date_table_column_text));
        dateColumn.setTextSize(headerFontSize);
        dateColumn.setTextColor(textColor);
        dateColumn.setTypeface(standardTypeface);
        dateColumn.setPadding(20, 0, 0 , 0);

        TextView loanBalanceAmountColumn = new TextView(ResultActivity.this);
        loanBalanceAmountColumn.setText(getResources().getString(R.string.balance_table_column_text));
        loanBalanceAmountColumn.setTextSize(headerFontSize);
        loanBalanceAmountColumn.setTextColor(textColor);
        loanBalanceAmountColumn.setSingleLine(false);
        loanBalanceAmountColumn.setTypeface(standardTypeface);
        loanBalanceAmountColumn.setPadding(20, 0 , 0, 0);

        TextView interestAmountColumn = new TextView(ResultActivity.this);
        interestAmountColumn.setText(getResources().getString(R.string.interest_table_column_text));
        interestAmountColumn.setTextSize(headerFontSize);
        interestAmountColumn.setTextColor(textColor);
        interestAmountColumn.setTypeface(standardTypeface);
        interestAmountColumn.setSingleLine(false);
        interestAmountColumn.setPadding(20, 0 ,0 ,0);

        TextView principalAmountColumn = new TextView(ResultActivity.this);
        principalAmountColumn.setText(getResources().getString(R.string.principal_table_column_text));
        principalAmountColumn.setTextSize(headerFontSize);
        principalAmountColumn.setTextColor(textColor);
        principalAmountColumn.setTypeface(standardTypeface);
        principalAmountColumn.setSingleLine(false);
        principalAmountColumn.setPadding(20, 0,0,0);

        TextView paymentAmountColumn = new TextView(ResultActivity.this);
        paymentAmountColumn.setText(getResources().getString(R.string.payment_amount_table_column_text));
        paymentAmountColumn.setTextSize(headerFontSize);
        paymentAmountColumn.setTextColor(textColor);
        paymentAmountColumn.setTypeface(standardTypeface);
        paymentAmountColumn.setSingleLine(false);
        paymentAmountColumn.setPadding(20, 0 , 0 ,0);

        header.addView(paymentNumberColumn);

        if (showPaymentDate()) {
            header.addView(dateColumn);
        }
        header.addView(loanBalanceAmountColumn);
        header.addView(interestAmountColumn);
        header.addView(principalAmountColumn);
        header.addView(paymentAmountColumn);

        tl.addView(header);
    }

    /**
     * Builds table content
     * @param tableLayout
     */
    // TODO Too complex method - refactor
    @SuppressLint("SetTextI18n")
    private void buildAmortizationTableContent(TableLayout tableLayout) {
        Integer paymentNumber = 0;

        int textColor = getResources().getColor(R.color.coolDarkColor);
        int textSize = 12;

        Typeface standardTypeface = getStandardTypeface();
        Typeface boldTypeface = getBoldTypeface();

        Drawable cellBackground = getResources().getDrawable(R.drawable.amortization_cell_background);
        Drawable cellBackgroundWithoutStroke = getResources().getDrawable(R.drawable.amortization_cell_background_without_stroke);
        Drawable earlyPaymentBackground = getResources().getDrawable(R.drawable.calc_button);

        for(MonthlyPayment payment : amortization.getMonthlyPayments()) {
            TableRow row = new TableRow(ResultActivity.this);

            row.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(57);

            int minHeight = 150; // TODO temp
            TextView currentPaymentNumber = new TextView(ResultActivity.this);

            String currentPaymentNumberText = (++paymentNumber).toString();

            currentPaymentNumber.setText(currentPaymentNumberText);
            currentPaymentNumber.setTypeface(standardTypeface);
            currentPaymentNumber.setTextColor(textColor);
            currentPaymentNumber.setTextSize(textSize);
            currentPaymentNumber.setBackground(cellBackground);
            currentPaymentNumber.setPadding(10, 10, 0, 10);
            currentPaymentNumber.setMinHeight(minHeight);

            TextView paymentDate = new TextView(ResultActivity.this);
            if (showPaymentDate()) {
                assert payment.getPaymentDate() != null;
                paymentDate.setText(CustomDateUtils.convertToDisplayingFormat(payment.getPaymentDate()));
                paymentDate.setTypeface(standardTypeface);
                paymentDate.setTextColor(textColor);
                paymentDate.setTextSize(textSize);
                paymentDate.setBackground(cellBackground);
                paymentDate.setPadding(10, 10, 10, 10);
                paymentDate.setMinHeight(minHeight);
            }

            TextView loanAmount = new TextView(ResultActivity.this);
            loanAmount.setText(DECIMAL_FORMAT.format(payment.getLoanBalanceAmount()));
            loanAmount.setTypeface(standardTypeface);
            loanAmount.setTextColor(textColor);
            loanAmount.setTextSize(textSize);
            loanAmount.setBackground(cellBackground);
            loanAmount.setPadding(10, 10 ,0 ,10);
            loanAmount.setMinHeight(minHeight);

            TextView interestAmount = new TextView(ResultActivity.this);
            interestAmount.setText(DECIMAL_FORMAT.format(payment.getInterestPaymentAmount()));
            interestAmount.setTypeface(standardTypeface);
            interestAmount.setTextColor(textColor);
            interestAmount.setTextSize(textSize);
            interestAmount.setBackground(cellBackground);
            interestAmount.setPadding(10, 10 ,5 ,10);
            interestAmount.setMinHeight(minHeight);

            TextView principalAmount = new TextView(ResultActivity.this);
            principalAmount.setText(DECIMAL_FORMAT.format(payment.getDebtPaymentAmount()));
            principalAmount.setTypeface(standardTypeface);
            principalAmount.setTextColor(textColor);
            principalAmount.setTextSize(textSize);
            principalAmount.setBackground(cellBackground);
            principalAmount.setPadding(10, 10 ,0 ,10);
            principalAmount.setMinHeight(minHeight);

            TableLayout innerTable = new TableLayout(this);
            innerTable.setBackground(cellBackground);

            TableRow innerRowForAmount = new TableRow(this);
            innerTable.addView(innerRowForAmount);

            TextView paymentAmount = new TextView(ResultActivity.this);
            paymentAmount.setText(DECIMAL_FORMAT.format(payment.getPaymentAmount()));
            paymentAmount.setTypeface(standardTypeface);
            paymentAmount.setTextColor(textColor);
            paymentAmount.setTextSize(textSize);
            paymentAmount.setBackground(cellBackground);
            paymentAmount.setMinHeight(minHeight / 2);
            paymentAmount.setPadding(10, 10, 0, 10);
            innerRowForAmount.addView(paymentAmount);

            String earlyPaymentValue = getEarlyPaymentValue(payment);

            TextView earlyPayment = new TextView(this);
            earlyPayment.setText(earlyPaymentValue);
            earlyPayment.setTypeface(boldTypeface);
            earlyPayment.setTextColor(textColor);
            earlyPayment.setBackground(earlyPaymentBackground);
            earlyPayment.setMinHeight((minHeight / 2) - 2); // TODO What the fuck is this thing?
            earlyPayment.setPaintFlags(earlyPayment.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            earlyPayment.setPadding(10, 10 ,0 ,10);
            earlyPayment.setTextSize(11);

            earlyPayment.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    earlyPaymentAddOnClickCallback(v);
                }
            });

            TableRow innerRowForEarlyPayment = new TableRow(this);
            innerRowForEarlyPayment.addView(earlyPayment);
            innerRowForEarlyPayment.setTag(paymentNumber - 1);

            innerTable.addView(innerRowForEarlyPayment);

            row.addView(currentPaymentNumber);

            if (showPaymentDate()) {
                row.addView(paymentDate);
            }

            row.addView(loanAmount);
            row.addView(interestAmount);
            row.addView(principalAmount);
            row.addView(innerTable);

            tableLayout.addView(row);
        }

    }

    /**
     * Base type face
     * @return
     */
    private Typeface getStandardTypeface() {
        Typeface typeface;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            typeface = getResources().getFont(R.font.base_font);
        } else {
            typeface = ResourcesCompat.getFont(getApplicationContext(), R.font.base_font);
        }
        return typeface;
    }

    /**
     * Bold type face
     * @return
     */
    private Typeface getBoldTypeface() {
        Typeface typeface;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            typeface = getResources().getFont(R.font.bold_font);
        } else {
            typeface = ResourcesCompat.getFont(getApplicationContext(), R.font.bold_font);
        }
        return typeface;
    }

    /**
     * Sets visibility for all child for base layout
     * @param value
     */
    private void setVisibilityForAll(int value) {
        ConstraintLayout layout = (ConstraintLayout) findViewById(R.id.base_layout);
        for (int i = 0; i < layout.getChildCount(); i++) {
            View child = layout.getChildAt(i);
            child.setVisibility(value);
        }
    }
}