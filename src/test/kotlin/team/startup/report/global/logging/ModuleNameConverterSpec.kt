package team.startup.report.global.logging

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ModuleNameConverterSpec :
    BehaviorSpec({
        given("우리 코드의 로거") {
            `when`("domain 아래 클래스면") {
                then("domain 다음 기능 이름을 쓴다") {
                    ModuleNameConverter.moduleOf("team.startup.report.domain.sample.service.SampleService") shouldBe "sample"
                }
            }
            `when`("global 아래 클래스면") {
                then("global 다음 이름을 쓴다") {
                    ModuleNameConverter.moduleOf("team.startup.report.global.security.SecurityConfig") shouldBe "security"
                }
            }
            `when`("최상위 패키지 클래스면") {
                then("바로 위 패키지를 쓴다") {
                    ModuleNameConverter.moduleOf("team.startup.report.Application") shouldBe "report"
                }
            }
        }
        given("라이브러리 로거") {
            then("바로 위 패키지를 쓴다") {
                ModuleNameConverter.moduleOf("com.zaxxer.hikari.HikariDataSource") shouldBe "hikari"
            }
            then("점이 없는 이름은 그대로 쓴다") {
                ModuleNameConverter.moduleOf("ROOT") shouldBe "ROOT"
            }
        }
    })
