package kr.workbench;

import java.util.regex.Pattern;

/** Shared implementation-error observations; matching is not proof of SQL injection. */
public final class SqlErrors {
  private SqlErrors() {}
  public static final Pattern PATTERN = Pattern.compile("(?i)(org\\.springframework\\.jdbc\\.BadSqlGrammarException[^\\n]{0,120}|Syntax error in SQL statement[^\\n]{0,120}|SQL syntax[^\\n]{0,120}|ORA-\\d{5}[^\\n]{0,120}|org\\.h2\\.jdbc[^\\n]{0,120}|Unclosed quotation mark[^\\n]{0,120}|unterminated quoted string[^\\n]{0,120}|SQLite(?:Exception|3?::SQLException|_(?:ERROR|MISUSE|CONSTRAINT|CORRUPT|NOTADB|AUTH|PERM))[^\\n]{0,120}|You have an error in your SQL syntax[^\\n]{0,120}|SQLSTATE(?:\\[|:)[^\\n]{0,120}|SequelizeDatabaseError[^\\n]{0,120}|PSQLException[^\\n]{0,120})");
}
