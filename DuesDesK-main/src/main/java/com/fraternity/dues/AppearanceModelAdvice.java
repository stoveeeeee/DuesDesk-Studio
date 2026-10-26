package com.fraternity.dues;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class AppearanceModelAdvice {
    private final Store store;

    public AppearanceModelAdvice(Store store) {
        this.store = store;
    }

    @ModelAttribute
    public void addAppearanceMode(Model model) {
        model.addAttribute("appearanceMode", store.getSettings().getAppearanceMode());
        model.addAttribute("chapterName", store.getSettings().getChapterName());
    }

    @ModelAttribute
    public void addActiveNav(Model model, HttpServletRequest request) {
        String path = request.getRequestURI();
        String active = null;
        if (path.equals("/") || path.equals("/index")) {
            active = "dues";
        } else if (path.equals("/rent")) {
            active = "rent";
        } else if (path.equals("/numbers")) {
            active = "numbers";
        } else if (path.startsWith("/numbers/flow")) {
            active = "flow";
        } else if (path.equals("/upload-docs")) {
            active = "upload";
        } else if (path.equals("/calendar")) {
            active = "calendar";
        } else if (path.equals("/finance-tools")) {
            active = "finance";
        } else if (path.equals("/chair-budgeting")) {
            active = "chair";
        } else if (path.equals("/charges")) {
            active = "charges";
        } else if (path.equals("/activity")) {
            active = "activity";
        } else if (path.equals("/presales")) {
            active = "presales";
        } else if (path.startsWith("/settings")) {
            active = "settings";
        }
        model.addAttribute("active", active);
    }
}
