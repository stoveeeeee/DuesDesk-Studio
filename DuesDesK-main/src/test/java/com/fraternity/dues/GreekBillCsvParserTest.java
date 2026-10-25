package com.fraternity.dues;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GreekBillCsvParserTest {

    @Test
    void rosterExportIsReadByHeaderNotPosition() {
        String csv = "﻿Member ID,First Name,Last Name,Email,Member Status,Membership Type,Graduation Year,Current Balance,Past Due Balance,Total Owed\n"
                + "101,John,Smith,j@x.edu,Active,Initiate,2027,\"$1,200.50\",$300.00,\"$1,500.50\"\n"
                + "102,Ann,Lee,a@x.edu,Inactive,Initiate,2026,,,\n"
                + "103,Bo,\"Jones, Jr\",b@x.edu,Active,New Member,2028,,,$0.00\n";
        List<GreekBillCsvParser.Row> rows = GreekBillCsvParser.parse(csv);
        assertEquals(2, rows.size());
        assertEquals("John Smith", rows.get(0).name());
        assertEquals(1500.50, rows.get(0).totalOwed(), 0.001);
        assertEquals(300.0, rows.get(0).pastDue(), 0.001);
        assertNull(rows.get(0).phone());
        assertEquals("Bo Jones, Jr", rows.get(1).name());
    }

    @Test
    void currentBalanceUsedWhenNoTotalOwed() {
        List<GreekBillCsvParser.Row> rows = GreekBillCsvParser.parse(
                "Last Name,First Name,Past Due Balance,Current Balance\nSmith,John,50,200\n");
        assertEquals(200.0, rows.get(0).totalOwed(), 0.001);
        assertEquals(50.0, rows.get(0).pastDue(), 0.001);
    }

    @Test
    void transactionExportIsRejected() {
        String csv = "Date,Reference Number,Description,Member Name,Member ID,Amount\n2026-09-01,R1,Fall Dues,John Smith,101,150.00\n";
        assertThrows(IllegalArgumentException.class, () -> GreekBillCsvParser.parse(csv));
    }

    @Test
    void legacyPositionalFormatStillWorks() {
        List<GreekBillCsvParser.Row> rows = GreekBillCsvParser.parse(
                "Name,Phone,Total Owed,Past Due\nJohn Smith,555-1234,$100,$25\nbad,row\n");
        assertEquals(1, rows.size());
        assertEquals("555-1234", rows.get(0).phone());
        assertEquals(25.0, rows.get(0).pastDue(), 0.001);
        List<GreekBillCsvParser.Row> pipe = GreekBillCsvParser.parse("John Smith|555|100|0|40\n");
        assertEquals(40.0, pipe.get(0).paidThisMonth(), 0.001);
    }
}
