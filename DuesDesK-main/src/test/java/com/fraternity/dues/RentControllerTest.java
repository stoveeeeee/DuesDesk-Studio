package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

class RentControllerTest {

    @Test
    void settingsPageProvidesDispatchLog() {
        Store store = mock(Store.class);
        when(store.getSettings()).thenReturn(new AppSettings());
        when(store.getSmsLogs()).thenReturn(List.of(new SmsLog()));
        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("settings", new DashboardController(store, new SmsService(store)).settingsPage(model));
        assertEquals(1, ((List<?>) model.getAttribute("smsLogs")).size());
    }

    @Test
    void rentPageProvidesCommunicationsData() {
        Store store = mock(Store.class);
        AppSettings settings = new AppSettings();
        Resident muted = new Resident("1", "Muted", "+15550000001", "1A", 500, 0, 0);
        muted.setMuteReminders(true);
        Resident unmuted = new Resident("2", "Unmuted", "+15550000002", "1B", 500, 0, 0);
        when(store.getSettings()).thenReturn(settings);
        when(store.getResidents()).thenReturn(new java.util.ArrayList<>(List.of(muted, unmuted)));
        when(store.getSmsRemainingToday()).thenReturn(12);
        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("rent", new RentController(store, new SmsService(store)).rent(null, model));
        assertEquals(12, model.getAttribute("smsRemainingToday"));
        assertEquals(1L, model.getAttribute("unmutedResidentCount"));
        assertEquals(settings, model.getAttribute("settings"));
    }

    @Test
    void addingRentChargesValidatesInputAndReportsHowManyResidentsWereCharged() {
        Store store = mock(Store.class);
        when(store.addResidentCharges(List.of("r1", "r2"), "Utilities", 25, "")).thenReturn(2);
        RentController controller = new RentController(store, new SmsService(store));
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        assertEquals("redirect:/rent", controller.addCharges(List.of("r1", "r2"), " Utilities ", 25, "", redirect));
        assertEquals("Added Utilities ($25.00) to 2 residents' rent bills.", redirect.getFlashAttributes().get("toast"));

        assertEquals("redirect:/rent#rentCharges", controller.addCharges(List.of("r1"), "Fee", -5, "", new RedirectAttributesModelMap()));
        assertEquals("redirect:/rent#rentCharges", controller.addCharges(List.of("r1"), "Fee", 5, "13/45/2026", new RedirectAttributesModelMap()));
        verify(store, org.mockito.Mockito.never()).addResidentCharges(List.of("r1"), "Fee", -5, "");
    }

    @Test
    void savingRentReminderTemplateReturnsToRentCommunications() {
        Store store = mock(Store.class);
        AppSettings settings = new AppSettings();
        when(store.getSettings()).thenReturn(settings);
        RentController controller = new RentController(store, new SmsService(store));

        String view = controller.updateRentTemplate("Rent is due for {name}", new RedirectAttributesModelMap());

        assertEquals("redirect:/rent#rentCommunications", view);
        assertEquals("Rent is due for {name}", settings.getRentSmsTemplate());
        verify(store).save();
    }

    @Test
    void houseAnnouncementReturnsToRentCommunicationsAfterDispatch() {
        Store store = mock(Store.class);
        SmsService smsService = new SmsService(store) {
            @Override
            public synchronized int broadcastToResidents(String message) {
                return 3;
            }
        };
        RentController controller = new RentController(store, smsService);

        String view = controller.announce("Trash pickup is Thursday", new RedirectAttributesModelMap());

        assertEquals("redirect:/rent#rentCommunications", view);
    }
}
