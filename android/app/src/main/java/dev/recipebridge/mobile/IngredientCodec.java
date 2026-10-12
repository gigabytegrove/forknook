package dev.recipebridge.mobile;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative ingredient parsing: never silently discard unrecognized wording. */
final class IngredientCodec {
    private static final Pattern NUMBER = Pattern.compile("^([0-9]+(?:[.,][0-9]+)?(?:\\s+[0-9]+/[0-9]+)?|[0-9]+/[0-9]+|[¼½¾⅓⅔⅛⅜⅝⅞])(?:\\s+|$)");
    private static final Pattern UNIT = Pattern.compile("^(cups?|tbsps?|tablespoons?|tsps?|teaspoons?|oz|ounces?|lbs?|pounds?|kg|g|grams?|ml|milliliters?|liters?|litres?|l|cloves?|cans?|jars?|slices?|sticks?|pinches?|pieces?|packs?|packages?|sprigs?|bunches?|heads?|stalks?)\\b\\s*", Pattern.CASE_INSENSITIVE);

    static JSONObject parse(String raw) throws Exception {
        String original = raw.trim();
        if (original.isEmpty()) throw new IllegalArgumentException("Blank ingredient");
        String remainder = original;
        String quantity = null;
        String unit = null;
        Matcher match = NUMBER.matcher(remainder);
        if (match.find()) {
            quantity = match.group(1);
            remainder = remainder.substring(match.end()).trim();
            Matcher unitMatch = UNIT.matcher(remainder);
            if (unitMatch.find()) {
                unit = unitMatch.group(1);
                remainder = remainder.substring(unitMatch.end()).trim();
            }
        }
        if (remainder.isEmpty()) remainder = original;
        JSONObject json = new JSONObject().put("name", remainder).put("raw", original).put("optional", false);
        if (quantity != null) json.put("quantity", quantity);
        if (unit != null) json.put("unit", unit);
        return json;
    }
}
