package com.fraternity.dues;

import java.math.BigDecimal;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class NumberInputWebConfig implements WebMvcConfigurer {
    private static final Pattern GROUPED_NUMBER =
            Pattern.compile("[+-]?(?:(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d*)?|\\.\\d+)");

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, Double.class, value -> Double.valueOf(normalize(value)));
        registry.addConverter(String.class, Float.class, value -> Float.valueOf(normalize(value)));
        registry.addConverter(String.class, Integer.class, value -> Integer.valueOf(normalize(value)));
        registry.addConverter(String.class, Long.class, value -> Long.valueOf(normalize(value)));
        registry.addConverter(String.class, BigDecimal.class, value -> new BigDecimal(normalize(value)));
    }

    static String normalize(String value) {
        String trimmed = value.trim();
        if (trimmed.indexOf(',') >= 0) {
            if (!GROUPED_NUMBER.matcher(trimmed).matches()) {
                throw new NumberFormatException("Use commas only as thousands separators.");
            }
            return trimmed.replace(",", "");
        }
        return trimmed;
    }
}
