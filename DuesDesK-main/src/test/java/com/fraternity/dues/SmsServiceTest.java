package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SmsServiceTest {
    @Test
    void reminderPreviewIncludesConfiguredDueDateAndSkipsMutedMembers() {
        Store store = mock(Store.class);
        AppSettings settings = new AppSettings();
        settings.setReminderDaysBeforeDue(3);
        settings.setReminderDaysAfterDue(1);
        when(store.getSettings()).thenReturn(settings);

        Member dueSoon = new Member("member-1", "Alex Member", "555-000-0000",
                100, 0, 0, "Payment Plan", "Never", false);
        ChargeRecord charge = new ChargeRecord("charge-1", LocalDate.now().toString(),
                "Semester dues", 40, 0);
        charge.setDueDate(LocalDate.now().plusDays(3).toString());
        dueSoon.setCharges(List.of(charge));
        Member muted = new Member("member-2", "Sam Member", "555-000-0001",
                80, 80, 0, "Delinquent", "Never", true);
        when(store.getMembers()).thenReturn(List.of(dueSoon, muted));

        List<SmsService.ReminderCandidate> preview = new SmsService(store).reminderPreview();

        assertEquals(1, preview.size());
        assertEquals("Alex Member", preview.getFirst().member().getName());
        assertEquals("Due soon: Semester dues", preview.getFirst().reason());
        assertEquals(40, preview.getFirst().amount(), 0.001);
    }

    @Test
    void statusBulkTextIncludesOnlyUnmutedMembersWithExactStatus() {
        Store store = mock(Store.class);
        Member delinquent = new Member("member-1", "Alex Member", "555-000-0000",
                100, 50, 0, "Delinquent", "Never", false);
        Member paid = new Member("member-2", "Sam Member", "555-000-0001",
                0, 0, 0, "Paid in Full", "Never", false);
        Member muted = new Member("member-3", "Pat Member", "555-000-0002",
                100, 50, 0, "Delinquent", "Never", true);
        when(store.getMembers()).thenReturn(List.of(delinquent, paid, muted));
        when(store.getSmsRemainingToday()).thenReturn(10);
        when(store.getTwilioCredentials()).thenReturn(null);
        when(store.reserveSmsSlot()).thenReturn(true);
        SmsService service = new SmsService(store);
        ReflectionTestUtils.setField(service, "simulationMode", true);

        SmsService.BulkMessageResult result =
                service.sendMessageToMembersByStatus("Delinquent", "Hello {name}");

        assertEquals(1, result.eligible());
        assertEquals(1, result.sent());
        assertEquals(0, result.failed());
        verify(store).reserveSmsSlot();
        verify(store).addSmsLog(org.mockito.ArgumentMatchers.argThat(log ->
                "Alex Member".equals(log.getMemberName()) && "SIMULATED".equals(log.getDeliveryMode())));
    }
}
