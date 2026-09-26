package com.pglens.server.databases;

import com.pglens.server.auth.Tokens;
import com.pglens.server.errors.Errors.Conflict;
import com.pglens.server.errors.Errors.Invalid;
import com.pglens.server.errors.Errors.NotFound;
import com.pglens.server.persistence.MonitoredDb;
import com.pglens.server.persistence.MonitoredDbRepository;
import com.pglens.server.persistence.MonitoredDbRepository.Registration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registering monitored databases and their agent tokens — the API that replaces the {@code make
 * register} SQL bootstrap (backlog B10, ADR-0044). The server never connects to a monitored
 * database: registering one only mints the token its agent presents; the connection details stay
 * with the agent.
 */
@Service
public class DatabaseService {

  /** Whether the agent is sending data. */
  public enum AgentStatus {
    /** Registered, but no batch has ever arrived. */
    NEVER_CONNECTED,
    /** A batch arrived within the stale-after window. */
    CONNECTED,
    /** The last batch is older than the stale-after window — the agent may be down. */
    STALE
  }

  /** A registered database with its agent's liveness. */
  public record Database(
      String name, String host, Instant createdAt, Instant lastIngestAt, AgentStatus agent) {}

  /** A new or rotated agent token: returned exactly once. */
  public record AgentCredentials(Database database, String agentToken) {}

  // Same shape as the agent's PGLENS_DB_NAME: safe in a URL path and a shell snippet.
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private final MonitoredDbRepository repo;
  private final Clock clock;
  private final Duration staleAfter;

  public DatabaseService(
      MonitoredDbRepository repo,
      Clock clock,
      @Value("${pglens.api.agent-stale-after:5m}") Duration staleAfter) {
    this.repo = repo;
    this.clock = clock;
    this.staleAfter = staleAfter;
  }

  public List<Database> list() {
    Instant now = clock.instant();
    return repo.registrations().stream().map(r -> view(r, now)).toList();
  }

  public Database get(String name) {
    return repo.registration(name)
        .map(r -> view(r, clock.instant()))
        .orElseThrow(() -> notFound(name));
  }

  /** A registered database's id and name, for the read API; 404 if unknown. */
  public MonitoredDb resolve(String name) {
    return repo.registration(name)
        .map(r -> new MonitoredDb(r.id(), r.name()))
        .orElseThrow(() -> notFound(name));
  }

  /** Every registered database's name and id. */
  public List<MonitoredDb> all() {
    return repo.registrations().stream().map(r -> new MonitoredDb(r.id(), r.name())).toList();
  }

  @Transactional
  public AgentCredentials register(String name) {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new Invalid(
          "A database name is 1–64 characters: letters, digits, '.', '_' or '-'. The agent sends"
              + " the same name as PGLENS_DB_NAME.");
    }
    String token = Tokens.newToken(Tokens.AGENT_PREFIX);
    Registration r =
        repo.registerIfAbsent(name, Tokens.sha256Hex(token))
            .orElseThrow(
                () ->
                    new Conflict(
                        "A database named '"
                            + name
                            + "' is already registered. Rotate its token instead."));
    return new AgentCredentials(view(r, clock.instant()), token);
  }

  /** A new agent token; the old one stops working at once (restart the agent with the new one). */
  @Transactional
  public AgentCredentials rotateToken(String name) {
    String token = Tokens.newToken(Tokens.AGENT_PREFIX);
    if (!repo.replaceToken(name, Tokens.sha256Hex(token))) {
      throw notFound(name);
    }
    return new AgentCredentials(get(name), token);
  }

  /**
   * Deletes the database's registration and all of its history in PgLens's metadata store (the
   * monitored database is never touched). {@code confirm} must repeat the name.
   */
  @Transactional
  public void delete(String name, String confirm) {
    if (!name.equals(confirm)) {
      throw new Invalid(
          "Deleting removes all of this database's history from PgLens. Repeat its name as"
              + " ?confirm="
              + name
              + " to go ahead.");
    }
    if (!repo.delete(name)) {
      throw notFound(name);
    }
  }

  private Database view(Registration r, Instant now) {
    AgentStatus status =
        r.lastIngestAt() == null
            ? AgentStatus.NEVER_CONNECTED
            : r.lastIngestAt().isBefore(now.minus(staleAfter))
                ? AgentStatus.STALE
                : AgentStatus.CONNECTED;
    return new Database(r.name(), r.host(), r.createdAt(), r.lastIngestAt(), status);
  }

  private static NotFound notFound(String name) {
    return new NotFound("No database named '" + name + "' is registered.");
  }
}
