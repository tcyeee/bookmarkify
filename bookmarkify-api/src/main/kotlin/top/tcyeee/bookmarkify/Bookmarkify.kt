package top.tcyeee.bookmarkify

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.transaction.annotation.EnableTransactionManagement

@EnableScheduling
@SpringBootApplication
@EnableTransactionManagement
@ConfigurationPropertiesScan
class Bookmarkify

fun main(args: Array<String>) {
    runApplication<Bookmarkify>(*args)
}
