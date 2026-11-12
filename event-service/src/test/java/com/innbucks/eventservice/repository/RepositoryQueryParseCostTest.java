package com.innbucks.eventservice.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.query.DeclaredQuery;
import org.springframework.data.jpa.repository.query.QueryEnhancerFactories;
import org.springframework.data.repository.Repository;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every JPQL {@code @Query} in this service must be cheap for Spring Data to
 * parse, because it parses all of them while the application starts.
 *
 * <p>Spring Data JPA reads each declared query with its own ANTLR grammar
 * ({@code JpaQueryEnhancer.forHql}) when the repository is created, to derive
 * the count query and apply sorting. Its cost depends on the query's SHAPE, not
 * its length: the sales-status filter written as seven levels of nested
 * {@code AND}/{@code OR} brackets allocated about 900 MiB of short-lived
 * garbage there, against about 30 MiB for an ordinary list query. That churn
 * made G1 grow the heap to its maximum during startup, and with the JVM's
 * ~330 MiB outside the heap the pod passed its limit: production's
 * event-service was OOMKilled at 640Mi and again at 768Mi (2026-11-12), and
 * every event read failed until it was rolled back.
 *
 * <p>This test parses each query exactly as startup does and fails on one that
 * allocates more than {@link #MAX_MIB}. A query that trips it is reshaped (flat
 * alternatives, or one query per case), never given a bigger budget here.
 */
class RepositoryQueryParseCostTest {

    /**
     * Ordinary queries here cost 30–60 MiB, the flattened sales-status filter
     * about 215 MiB, the nested one that took production down about 900 MiB.
     */
    private static final long MAX_MIB = 400;

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    @Test
    void everyDeclaredQuery_isCheapToParseAtStartup() throws Exception {
        List<NamedQuery> queries = declaredJpqlQueries();
        assertThat(queries)
                .as("the scan must find this service's @Query methods")
                .hasSizeGreaterThan(10)
                .extracting(NamedQuery::name)
                .contains("EventRepository.findByTenantUserUuidAndSalesStatus");

        // The parser's first queries pay a one-time warm-up (its prediction
        // cache), which is not a property of any one query.
        for (int i = 0; i < 3; i++) {
            parse(queries.get(0).jpql());
        }

        List<String> tooExpensive = new ArrayList<>();
        for (NamedQuery query : queries) {
            long mib = allocatedMib(query.jpql());
            if (mib > MAX_MIB) {
                tooExpensive.add(query.name() + " allocated " + mib + " MiB");
            }
        }
        assertThat(tooExpensive)
                .as("@Query methods Spring Data parses too expensively at startup (limit %d MiB); "
                        + "flatten the nesting or split the query", MAX_MIB)
                .isEmpty();
    }

    private static long allocatedMib(String jpql) {
        long before = THREADS.getCurrentThreadAllocatedBytes();
        parse(jpql);
        return (THREADS.getCurrentThreadAllocatedBytes() - before) >> 20;
    }

    /** What repository creation does with a declared JPQL query under Hibernate. */
    private static void parse(String jpql) {
        QueryEnhancerFactories.hql().create(DeclaredQuery.jpqlQuery(jpql));
    }

    private static List<NamedQuery> declaredJpqlQueries() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                        return definition.getMetadata().isInterface();
                    }
                };
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));

        List<NamedQuery> queries = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(
                RepositoryQueryParseCostTest.class.getPackageName())) {
            Class<?> repository = Class.forName(definition.getBeanClassName());
            for (Method method : repository.getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query != null && !query.nativeQuery() && !query.value().isBlank()) {
                    queries.add(new NamedQuery(repository.getSimpleName() + "." + method.getName(), query.value()));
                }
            }
        }
        return queries;
    }

    private record NamedQuery(String name, String jpql) {
    }
}
