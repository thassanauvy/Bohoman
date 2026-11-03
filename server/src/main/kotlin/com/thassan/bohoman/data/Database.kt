package com.thassan.bohoman.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway

object Database {
    fun open(url: String, user: String, password: String): HikariDataSource {
        require(url.startsWith("jdbc:postgresql:")) { "DATABASE_URL must be a PostgreSQL JDBC URL" }
        val dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 10
            minimumIdle = 1
            connectionTimeout = 10_000
            poolName = "bohoman-db"
        })
        try {
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(true).load().migrate()
            return dataSource
        } catch (failure: Exception) {
            dataSource.close()
            throw failure
        }
    }
}
