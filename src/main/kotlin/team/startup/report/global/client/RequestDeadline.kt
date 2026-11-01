package team.startup.report.global.client

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import team.startup.report.global.exception.ExpectedException
import java.time.Duration
import java.time.Instant

/**
 * 엑셀 요청 단위 데드라인(#114). 엑셀 인터셉터가 요청 시작에 [start]로 정하고, 공급자 호출([ClientResilience.Guard.run])마다 [check]한다.
 * Report는 파일을 다 만들 때까지 아무것도 보내지 않아 Gateway upstream idle 60초가 사실상 전체 제한이다. 그 뒤의 작업은 Gateway가 이미 504로
 * 끊은 요청의 남은 일(orphaned work)이므로 더 부르지 않는다. 지금 성공하는 다운로드를 깨지 않게 60초에 가깝게 둔다.
 * 데드라인이 없으면(요청 밖, 엑셀 외 경로) 확인하지 않는다.
 */
object RequestDeadline {
    private const val ATTRIBUTE = "report.requestDeadline"

    // ponytail: 이미 시작한 호출 하나는 끝까지 기다린다(최대 connect 3s + read 10s). 남은 시간으로 호출별 read-timeout을 줄이는 것은 하지 않았다
    val BUDGET: Duration = Duration.ofSeconds(58)

    fun start(
        request: HttpServletRequest,
        deadline: Instant = Instant.now() + BUDGET,
    ) = request.setAttribute(ATTRIBUTE, deadline)

    fun check() {
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request ?: return
        val deadline = request.getAttribute(ATTRIBUTE) as? Instant ?: return
        if (Instant.now() >= deadline) {
            throw ExpectedException(HttpStatus.GATEWAY_TIMEOUT, "엑셀 생성 시간이 초과되었습니다. 범위를 줄여 다시 요청해 주세요.")
        }
    }
}
