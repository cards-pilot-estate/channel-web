package com.channelweb.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The question pages and their server-side rules (docs/channels/web-channel.md §3.2.3). Each error
 * is a field and a code the SPA turns into a sentence in the customer's language.
 */
public final class Sections {

    public static final List<String> ORDER = List.of("about-you", "address", "work", "card");
    public static final Set<String> CARD_TYPES = Set.of("BASIC", "GOLD", "PLATINUM", "SIGNATURE");
    private static final Set<String> EMPLOYMENT = Set.of("SALARIED", "SELF_EMPLOYED", "BUSINESS_OWNER");
    private static final Pattern NAME = Pattern.compile("^[\\p{L}][\\p{L} .'-]{0,98}[\\p{L}.]$");
    private static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final Pattern PINCODE = Pattern.compile("^[1-9][0-9]{5}$");
    private static final Pattern TEXT = Pattern.compile("^[\\p{L}0-9 ,./#()'-]{2,100}$");

    public record FieldError(String field, String code) { }

    public record Result(Map<String, Object> values, List<FieldError> errors) {
        public boolean ok() { return errors.isEmpty(); }
    }

    private Sections() { }

    public static Result validate(String section, Map<String, Object> in, LocalDate today) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<FieldError> errors = new ArrayList<>();
        switch (section) {
            case "about-you" -> {
                text(in, "fullName", NAME, true, out, errors);
                String pan = str(in, "pan").toUpperCase().replace(" ", "");
                if (pan.isEmpty()) errors.add(new FieldError("pan", "required"));
                else if (!PAN.matcher(pan).matches()) errors.add(new FieldError("pan", "format"));
                else out.put("pan", pan);
                String dob = str(in, "dateOfBirth");
                try {
                    LocalDate d = LocalDate.parse(dob);
                    int age = Period.between(d, today).getYears();
                    if (age < 18) errors.add(new FieldError("dateOfBirth", "tooYoung"));
                    else if (age > 100) errors.add(new FieldError("dateOfBirth", "format"));
                    else out.put("dateOfBirth", d.toString());
                } catch (DateTimeParseException e) {
                    errors.add(new FieldError("dateOfBirth", dob.isEmpty() ? "required" : "format"));
                }
                text(in, "email", EMAIL, true, out, errors);
            }
            case "address" -> {
                text(in, "addressLine1", TEXT, true, out, errors);
                text(in, "addressLine2", TEXT, false, out, errors);
                text(in, "city", TEXT, true, out, errors);
                text(in, "state", TEXT, true, out, errors);
                text(in, "pincode", PINCODE, true, out, errors);
            }
            case "work" -> {
                String type = str(in, "employmentType");
                if (!EMPLOYMENT.contains(type)) errors.add(new FieldError("employmentType", "required"));
                else out.put("employmentType", type);
                text(in, "employerName", TEXT, !"SELF_EMPLOYED".equals(type), out, errors);
                money(in, "monthlyIncome", true, out, errors);
                money(in, "monthlyObligation", false, out, errors);
            }
            case "card" -> {
                String card = str(in, "cardType");
                if (!CARD_TYPES.contains(card)) errors.add(new FieldError("cardType", "required"));
                else out.put("cardType", card);
                if (!Boolean.TRUE.equals(in.get("kfsAcknowledged"))) errors.add(new FieldError("kfsAcknowledged", "required"));
                else out.put("kfsAcknowledged", true);
            }
            default -> errors.add(new FieldError("section", "unknown"));
        }
        return new Result(out, errors);
    }

    private static String str(Map<String, Object> in, String field) {
        Object v = in.get(field);
        return v == null ? "" : v.toString().trim();
    }

    private static void text(Map<String, Object> in, String field, Pattern p, boolean required,
                             Map<String, Object> out, List<FieldError> errors) {
        String v = str(in, field);
        if (v.isEmpty()) {
            if (required) errors.add(new FieldError(field, "required"));
        } else if (!p.matcher(v).matches()) {
            errors.add(new FieldError(field, "format"));
        } else {
            out.put(field, v);
        }
    }

    private static void money(Map<String, Object> in, String field, boolean required, Map<String, Object> out,
                              List<FieldError> errors) {
        String v = str(in, field).replace(",", "");
        if (v.isEmpty()) {
            if (required) errors.add(new FieldError(field, "required"));
            else out.put(field, 0);
            return;
        }
        try {
            BigDecimal amount = new BigDecimal(v);
            if (amount.signum() < 0 || amount.compareTo(new BigDecimal("100000000")) > 0 || amount.scale() > 2) {
                errors.add(new FieldError(field, "range"));
            } else {
                out.put(field, amount);
            }
        } catch (NumberFormatException e) {
            errors.add(new FieldError(field, "format"));
        }
    }
}
