package com.pglens.server.web;

import com.pglens.server.databases.DatabaseService;
import com.pglens.server.databases.DatabaseService.AgentCredentials;
import com.pglens.server.databases.DatabaseService.Database;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Monitored-database registration (B10, ADR-0044). Reading is for any logged-in user; registering,
 * rotating and deleting need ADMIN (enforced in {@link SecurityConfig}).
 */
@RestController
@RequestMapping("/api/v1/databases")
class DatabaseController {

  record NewDatabase(String name) {}

  private final DatabaseService databases;

  DatabaseController(DatabaseService databases) {
    this.databases = databases;
  }

  @GetMapping
  List<Database> list() {
    return databases.list();
  }

  @GetMapping("/{name}")
  Database get(@PathVariable String name) {
    return databases.get(name);
  }

  /** Registers a database; the agent token is in the response exactly once. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  AgentCredentials register(@RequestBody NewDatabase body) {
    return databases.register(body.name());
  }

  @PostMapping("/{name}/token")
  AgentCredentials rotateToken(@PathVariable String name) {
    return databases.rotateToken(name);
  }

  /** Deletes the database's history from PgLens; {@code ?confirm=<name>} is required. */
  @DeleteMapping("/{name}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable String name, @RequestParam(required = false) String confirm) {
    databases.delete(name, confirm);
  }
}
