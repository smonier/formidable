package org.jahia.modules.formidable.jexperience.engine.util;

/** The one JCR-SQL2 shape this module writes: every node of a type under a path. */
public final class Sql2 {

    private Sql2() {
    }

    /**
     * The path is a SQL2 string literal: a quote in it is doubled, the rule of {@code JCRContentUtils.sqlEncode} —
     * applied here by hand because that class does not load outside a running Jahia, and the queries have unit tests.
     */
    public static String descendantsOf(String type, String path) {
        return "SELECT * FROM [" + type + "] WHERE ISDESCENDANTNODE('" + path.replace("'", "''") + "')";
    }
}
