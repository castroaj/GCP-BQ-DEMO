package com.example.calls;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CallsController {
  private final CallsQueryService service;

  public CallsController(CallsQueryService service) {
    this.service = service;
  }

  // Parameters are read as raw strings so CallQuery owns validation and its error codes.
  @GetMapping("/query")
  public QueryResponse query(
      @RequestParam(required = false) String telephoneNumber,
      @RequestParam(required = false) String startDatetime,
      @RequestParam(required = false) String endDatetime) {
    return service.query(CallQuery.parse(telephoneNumber, startDatetime, endDatetime));
  }
}
