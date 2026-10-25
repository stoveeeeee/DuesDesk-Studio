package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.format.support.DefaultFormattingConversionService;

class NumberInputWebConfigTest {
    private final DefaultFormattingConversionService conversionService = conversionService();

    private static DefaultFormattingConversionService conversionService() {
        DefaultFormattingConversionService service = new DefaultFormattingConversionService();
        new NumberInputWebConfig().addFormatters(service);
        return service;
    }

    @Test
    void groupedAndUngroupedNumbersConvertForServerBinding() {
        assertEquals(1234.56, conversionService.convert(" 1,234.56 ", Double.class), 0.001);
        assertEquals(1234, conversionService.convert("1,234", Integer.class));
        assertEquals(1234.56f, conversionService.convert("1234.56", Float.class), 0.001);
        assertEquals(1234L, conversionService.convert("1,234", Long.class));
        assertEquals(new BigDecimal("1234.56"), conversionService.convert("1,234.56", BigDecimal.class));
    }

    @Test
    void invalidCommaGroupingFailsInsteadOfSilentlyChangingTheValue() {
        assertThrows(ConversionFailedException.class, () -> conversionService.convert("12,34.56", Double.class));
        assertThrows(ConversionFailedException.class, () -> conversionService.convert("1,,234", Integer.class));
        assertThrows(ConversionFailedException.class, () -> conversionService.convert("1,23,456", Long.class));
    }
}
