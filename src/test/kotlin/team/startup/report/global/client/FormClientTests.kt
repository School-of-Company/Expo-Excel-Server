package team.startup.report.global.client

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import team.startup.report.global.client.form.FormClient

/** 불투명 커서는 기존 RestClient처럼 한 번만 전부 인코딩해 넘긴다('%'를 이미 인코딩된 값으로 보지 않는다) */
class FormClientTests :
    StringSpec({
        "커서의 '%'·'/'·'+'·한글을 한 번만 인코딩한다" {
            StubProvider("t").use { form ->
                form.on("GET /internal/surveys/e1/public-answers?cursor=50%25%2F%ED%95%9C%2B&limit=1000", 404, "{}")

                FormClient(form.client()).publicSurveyAnswerPage("e1", "50%/한+") shouldBe null
            }
        }
    })
