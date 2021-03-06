package com.ragin.bdd.cucumber.glue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.ragin.bdd.cucumber.core.BaseCucumberCore;
import com.ragin.bdd.cucumber.core.DatabaseExecutorService;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * This class contains database related steps.
 */
@SuppressWarnings("SpringJavaAutowiredMembersInspection")
public class DatabaseGlue extends BaseCucumberCore {
    @Autowired
    protected LocalContainerEntityManagerFactoryBean entityManagerFactory;

    @Autowired
    DatabaseExecutorService databaseExecutorService;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Initialize the database with a file.
     *
     * @param pathToFile    path/filename of the file, which contains a liquibase script
     * @throws Exception    Error with executing database statements
     */
    @Given("that the database was initialized with the liquibase file {string}")
    @Transactional
    public void givenThatTheDatabaseWasInitializedWithLiquibaseFile(final String pathToFile) throws Exception {
        databaseExecutorService.executeLiquibaseScript(getFilePath(pathToFile));
    }

    /**
     * Execute a file which contains SQL statements
     *
     * @param pathToQueryFile       Path/filename to file with query (SQL)
     * @throws java.io.IOException  Unable to read file or to parse results
     */
    @Given("that the SQL statements from the SQL file {string} was executed")
    @Transactional
    public void givenThatTheDatabaseWasInitializedWithSQLFile(final String pathToQueryFile) throws IOException {
        // read file
        final String sqlStatements = readFile(pathToQueryFile);
        // execute query
        databaseExecutorService.executeSQL(sqlStatements);
    }

    /**
     * Ensure that the result of a query of a file is equal to a result list in a CSV file.
     *
     * @param pathToQueryFile   Path/filename to file with query (SQL)
     * @param pathToCsvFile     Path/filename to CSV file with result to compare
     * @throws IOException      Unable to read file or to parse results
     */
    @Then("I ensure that the result of the query of the file {string} is equal to the CSV file {string}")
    @Transactional
    public void thenEnsureThatResultOfQueryOfFileIsEqualToCSV(
            final String pathToQueryFile,
            final String pathToCsvFile
    ) throws IOException {
        // read file
        final String sqlStatements = readFile(pathToQueryFile);
        // execute query
        final List<Map<String, Object>> queryResults = generifyDatabaseJSONFiles(databaseExecutorService.executeQuerySQL(sqlStatements));

        // Generify the results to be database independent
        final Iterator<Map<String, Object>> iterator = new CsvMapper()
                .readerFor(Map.class)
                .with(CsvSchema.emptySchema().withHeader())
                .readValues(readFile(pathToCsvFile));

        // write data into list
        final List<Map<String, Object>> csvList = new ArrayList<>();
        while (iterator.hasNext()) {
            csvList.add(iterator.next());
        }

        final List<Map<String, Object>> data = generifyDatabaseJSONFiles(csvList);

        // Convert results to JSON to reuse compare
        final String expectedResultAsJSON = mapper.writeValueAsString(data);
        final String actualResultAsJSON = mapper.writeValueAsString(queryResults);

        // compare JSON
        assertJSONisEqual(expectedResultAsJSON, actualResultAsJSON);
    }

    /**
     * Generify the given JSON in order to be able to check it across several databases.
     * <p>This is required since not all databases return the column name in a common format, nor the saved value.</p>
     *
     * @see DatabaseGlue#generifyDatabaseColumnName(String)
     * @see DatabaseGlue#generifyDatabaseColumnValue(Object)
     * @param data List of database rows as Map with column name and columns value
     * @return the generified data to be database independent
     */
    private List<Map<String, Object>> generifyDatabaseJSONFiles(final List<Map<String, Object>> data) {
        return data.stream().map(
                original -> original
                        .entrySet()
                        .stream()
                        .filter(entry -> entry.getValue() != null)
                        .filter(entry -> !(entry.getValue() instanceof String) || StringUtils.isNotEmpty((String) entry.getValue()))
                        .collect(Collectors.toMap(
                                columnName -> generifyDatabaseColumnName(columnName.getKey()),
                                columnValue -> generifyDatabaseColumnValue(columnValue.getValue())
                        ))
        ).collect(Collectors.toList());
    }

    /**
     * Generify the given <code>columnName</code> for database independent compare.
     * <p>It applies the following modifications:</p>
     * <ul>
     *     <li>Makes it "uppercase", so that the comparison is case insensitive.</li>
     * </ul>
     *
     * @param columnName the columnName to be processed
     * @return the columnName processed to be database independent
     */
    private String generifyDatabaseColumnName(final String columnName) {
        return columnName.toUpperCase();
    }

    /**
     * Generify the given <code>columnValue</code> for database independent compare.
     * <p>It applies the following modifications:</p>
     * <ul>
     *     <li>Boolean values are mapped to either "0" or "1".</li>
     *     <li>"true" and "false" strings are mapped to either "0" or "1".</li>
     * </ul>
     *
     * @param columnValue the columnValue to be processed
     * @param <V> any object
     * @return the columnName processed to be database independent
     */
    private <V> Object generifyDatabaseColumnValue(final V columnValue) {
        if (columnValue instanceof Boolean) {
            return (Boolean) columnValue ? 1 : 0;
        }

        if (columnValue instanceof String) {
            if ("true".equalsIgnoreCase((String) columnValue)) {
                return 1;
            } else if ("false".equalsIgnoreCase((String) columnValue)) {
                return 0;
            }
        }

        return String.valueOf(columnValue);
    }
}
