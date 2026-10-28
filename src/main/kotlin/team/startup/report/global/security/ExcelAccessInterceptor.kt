package team.startup.report.global.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import team.startup.report.domain.excel.presentation.controller.EntryRecordExcelController
import team.startup.report.domain.excel.presentation.controller.ExcelController
import team.startup.report.domain.excel.presentation.controller.ExcelDownloadController
import team.startup.report.domain.excel.presentation.controller.ProgramAttendanceExcelController
import team.startup.report.domain.excel.presentation.controller.PublicSurveyExcelController
import team.startup.report.global.client.RequestDeadline
import team.startup.report.global.client.expo.ExpoClient
import team.startup.report.global.client.user.TraineeDetail
import team.startup.report.global.client.user.UserClient
import team.startup.report.global.exception.ExpectedException

// 클라이언트는 생성자 대신 addInterceptors에서 꺼낸다. Feign 클라이언트를 만들 때 자식 컨텍스트의 refresh 이벤트가
// WebMvc 설정을 먼저 초기화하므로, 생성자로 받으면 WebMvcConfigurer → 클라이언트 → WebMvc 설정 순환이 된다
@Configuration
class ExcelAccessConfiguration(
    private val expo: ObjectProvider<ExpoClient>,
    private val user: ObjectProvider<UserClient>,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(ExcelAccessInterceptor(expo.getObject(), user.getObject())).addPathPatterns("/excel/**")
    }
}

class ExcelAccessInterceptor(
    private val expo: ExpoClient,
    private val user: UserClient,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (handler !is HandlerMethod ||
            (
                !ExcelController::class.java.isAssignableFrom(handler.beanType) &&
                    !ExcelDownloadController::class.java.isAssignableFrom(handler.beanType) &&
                    !EntryRecordExcelController::class.java.isAssignableFrom(handler.beanType) &&
                    !ProgramAttendanceExcelController::class.java.isAssignableFrom(handler.beanType) &&
                    !PublicSurveyExcelController::class.java.isAssignableFrom(handler.beanType)
            )
        ) {
            return true
        }
        // 권한 확인 호출보다 먼저 정한다. 5개 엑셀 컨트롤러가 모두 이 인터셉터를 지난다
        RequestDeadline.start(request)
        val auth =
            SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
                ?: throw ExpectedException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.")
        val adminId =
            auth.token.subject
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: throw ExpectedException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.")
        val variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<*, *> ?: error("경로 변수 없음")
        var trainee: TraineeDetail? = null
        val expoId =
            (variables["expo_id"] ?: variables["expoId"]) as? String ?: run {
                val id = (variables["trainee_id"] as? String)?.toLongOrNull() ?: return true
                trainee = try {
                    user.trainee(id)?.also { check(it.traineeId == id && it.expoId.isNotBlank()) }
                } catch (_: Exception) {
                    throw ExpectedException(HttpStatus.BAD_GATEWAY, "연수자의 행사 정보를 확인할 수 없습니다.")
                } ?: throw ExpectedException(HttpStatus.NOT_FOUND, "해당 연수자를 찾을 수 없습니다.")
                trainee.expoId
            }
        expo.requireAdminAccess(expoId, adminId, "Bearer ${auth.token.tokenValue}")
        // 개인 출석부의 행사 확인에 쓴 원천을 같은 요청에서 재사용해 행사 변경/중복 조회를 막는다.
        trainee?.let { request.setAttribute(TRAINEE_ATTRIBUTE, it) }
        return true
    }

    companion object {
        private const val TRAINEE_ATTRIBUTE = "report.authorizedTrainee"

        fun authorizedTrainee(traineeId: Long): TraineeDetail? =
            (
                (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)
                    ?.request
                    ?.getAttribute(TRAINEE_ATTRIBUTE) as? TraineeDetail
            )?.also { check(it.traineeId == traineeId) }
    }
}
