package com.fraternity.dues;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ModelAndView uploadTooLarge(MaxUploadSizeExceededException exception) {
        log.warn("Rejected upload exceeding configured size limit", exception);
        ModelAndView page = new ModelAndView("error");
        page.setStatus(HttpStatus.PAYLOAD_TOO_LARGE);
        page.addObject("errorTitle", "Upload too large");
        page.addObject("errorMessage", "CSV uploads are limited to 5 MB. Choose a smaller file and try again.");
        return page;
    }

    /** A mistyped or outdated address: a normal "not found" page, not a logged crash. */
    @ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class})
    public ModelAndView notFound(Exception exception) {
        log.debug("No page for request: {}", exception.getMessage());
        ModelAndView page = new ModelAndView("error");
        page.setStatus(HttpStatus.NOT_FOUND);
        page.addObject("errorTitle", "Page not found");
        page.addObject("errorMessage", "That page doesn't exist. Use the button below to get back to your data.");
        return page;
    }

    /** e.g. reopening the address of a form submission; the action itself only works from its form. */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ModelAndView methodNotAllowed(Exception exception) {
        log.debug("Unsupported request method: {}", exception.getMessage());
        ModelAndView page = new ModelAndView("error");
        page.setStatus(HttpStatus.METHOD_NOT_ALLOWED);
        page.addObject("errorTitle", "That link only works from its form");
        page.addObject("errorMessage", "This address saves data, so it can't be opened directly. Go back and use the form instead.");
        return page;
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ModelAndView invalidRequest(Exception exception) {
        log.warn("Rejected invalid request parameters", exception);
        ModelAndView page = new ModelAndView("error");
        page.setStatus(HttpStatus.BAD_REQUEST);
        page.addObject("errorTitle", "Invalid request");
        page.addObject("errorMessage", "Some submitted values were missing or invalid. Check them and try again.");
        return page;
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView unexpectedFailure(Exception exception) {
        log.error("Unhandled application request failure", exception);
        ModelAndView page = new ModelAndView("error");
        page.setStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        page.addObject("errorTitle", "Request could not be completed");
        page.addObject("errorMessage", "DuesDesk could not complete that request. Your data was not silently discarded; check the application log and retry.");
        return page;
    }
}
