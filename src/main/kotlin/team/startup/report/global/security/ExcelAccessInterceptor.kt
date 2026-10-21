package team.startup.report.global.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
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
import team.startup.report.domain.excel.presentation.EntryRecordExcelController
import team.startup.report.domain.excel.presentation.ExcelController
import team.startup.report.domain.excel.presentation.ExcelDownloadController
import team.startup.report.domain.excel.presentation.ProgramAttendanceExcelController
import team.startup.report.global.client.ExpoClient
import team.startup.report.global.client.TraineeDetail
import team.startup.report.global.client.UserClient
import team.startup.report.global.exception.ExpectedException

@Configuration
class ExcelAccessConfiguration(
    private val expo: ExpoClient,
    private val user: UserClient,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(ExcelAccessInterceptor(expo, user)).addPathPatterns("/excel/**")
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
                    !ProgramAttendanceExcelController::class.java.isAssignableFrom(handler.beanType)
            )
        ) {
            return true
        }
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
