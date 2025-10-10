package com.cw2.nym.ai.provider.custom

import com.cw2.nym.ai.provider.openai.*
import com.cw2.nym.core.result.Result
import com.cw2.nym.core.exception.NymError
import com.cw2.nym.core.logging.NymLogger
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

/**
 * 自定义API的HTTP客户端实现
 *
 * 支持IDEA代理配置，处理认证和请求转发
 */
class CustomAPIHttpClient(private val config: CustomAPIConfig) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val proxy by lazy {
        try {
            // 使用 Java 标准 ProxySelector，IDEA 会自动配置系统代理
            val proxySelector = ProxySelector.getDefault()
            val uri = URI.create(config.buildEndpointUrl())
            val proxies = proxySelector.select(uri)

            // 获取第一个非直连的代理
            val selectedProxy = proxies.firstOrNull { it.type() != Proxy.Type.DIRECT }

            if (selectedProxy != null && selectedProxy.address() != null) {
                NymLogger.info("Build proxy", "使用代理: ${selectedProxy.type()} ${selectedProxy.address()}")
                selectedProxy
            } else {
                NymLogger.info("Build proxy", "未检测到代理配置，使用直接连接")
                Proxy.NO_PROXY
            }
        } catch (e: Exception) {
            NymLogger.error("Build proxy", "获取代理配置失败", error = e)
            Proxy.NO_PROXY
        }
    }

    private fun configureProxyAuthenticator() {
        // 设置全局代理认证器
        // IDEA 会通过 Authenticator.getDefault() 处理代理认证
        if (Authenticator.getDefault() == null) {
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    if (requestorType == RequestorType.PROXY) {
                        NymLogger.debug("Build client", "代理请求认证: $requestingHost:$requestingPort")
                        // 返回 null 让 IDEA 的默认认证处理器接管
                    }
                    return null
                }
            })
        }
    }

    fun sendRequest(request: OpenAIRequest): Result<OpenAIResponse> {
        return try {
            configureProxyAuthenticator()

            val clientBuilder = okhttp3.OkHttpClient.Builder()
                .connectTimeout(config.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(config.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .writeTimeout(config.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

            // 配置代理
            if (proxy != Proxy.NO_PROXY) {
                clientBuilder.proxy(proxy)

                // OkHttp 的代理认证器
                clientBuilder.proxyAuthenticator { route, response ->
                    val credential = if (route != null) {
                        val proxyAddress = route.proxy.address() as? InetSocketAddress
                        Authenticator.requestPasswordAuthentication(
                            proxyAddress?.hostString ?: route.address.url.host,
                            java.net.InetAddress.getByName(proxyAddress?.hostString ?: route.address.url.host),
                            proxyAddress?.port ?: route.address.url.port,
                            route.address.url.toUrl().protocol,
                            "Proxy Authentication",
                            route.address.url.scheme,
                            route.address.url.toUrl(),
                            Authenticator.RequestorType.PROXY
                        )
                    } else {
                        null
                    }

                    if (credential != null) {
                        val auth = okhttp3.Credentials.basic(credential.userName, String(credential.password))
                        response.request.newBuilder()
                            .header("Proxy-Authorization", auth)
                            .build()
                    } else {
                        null
                    }
                }
            }

            // 配置SSL验证
            if (!config.verifySSL) {
                clientBuilder.sslSocketFactory(
                    UnsafeSslSocketFactory.getUnsafeSSLContext().socketFactory,
                    UnsafeSslSocketFactory.getUnsafeTrustManager()
                )
                clientBuilder.hostnameVerifier { _, _ -> true }
                NymLogger.warn("Build client", "SSL验证已禁用，请确保在安全环境中使用")
            }

            val client = clientBuilder.build()

            val jsonRequest = json.encodeToString(OpenAIRequest.serializer(), request)
            val requestBody = jsonRequest.toRequestBody("application/json".toMediaType())

            val requestBuilder = okhttp3.Request.Builder()
                .url(config.buildEndpointUrl())
                .post(requestBody)

            // 添加认证头部和自定义头部
            config.getAuthHeaders().forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }

            val httpRequest = requestBuilder.build()

            NymLogger.debug("sendRequest", "发送请求到: ${config.buildEndpointUrl()}")
            if (proxy != Proxy.NO_PROXY) {
                NymLogger.debug("sendRequest", "通过代理: $proxy")
            }

            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    NymLogger.logError("HTTP请求失败", NymError.NetworkError.NetworkUnreachable(), mapOf(
                        "code" to response.code,
                        "message" to response.message,
                        "body" to errorBody,
                        "url" to config.buildEndpointUrl()
                    ))
                    return Result.error(
                        NymError.APIError.ServerError("HTTP ${response.code}: $errorBody")
                    )
                }

                val responseBody = response.body?.string() ?: ""
                val openAIResponse = json.decodeFromString(OpenAIResponse.serializer(), responseBody)
                NymLogger.debug("sendRequest", "请求成功完成")
                Result.success(openAIResponse)
            }

        } catch (e: Exception) {
            when (e) {
                is java.net.SocketTimeoutException -> {
                    NymLogger.logError("请求超时", NymError.NetworkError.ReadTimeout("ReadTimeout${config.timeoutMs}"), mapOf(
                        "timeout" to config.timeoutMs,
                        "url" to config.buildEndpointUrl()
                    ))
                    Result.error(NymError.NetworkError.ReadTimeout("${config.timeoutMs}"))
                }
                is java.net.UnknownHostException -> {
                    NymLogger.logError("主机解析失败", NymError.NetworkError.NetworkUnreachable(cause = e), mapOf(
                        "host" to config.buildEndpointUrl()
                    ))
                    Result.error(NymError.NetworkError.NetworkUnreachable(config.buildEndpointUrl()))
                }
                is java.net.ConnectException -> {
                    NymLogger.logError("连接失败", NymError.NetworkError.NetworkUnreachable(cause = e), mapOf(
                        "url" to config.buildEndpointUrl(),
                        "proxy" to proxy.toString()
                    ))
                    Result.error(NymError.NetworkError.NetworkUnreachable(config.buildEndpointUrl()))
                }
                else -> {
                    NymLogger.logError("网络请求异常", NymError.NetworkError.NetworkUnreachable(cause = e), mapOf(
                        "url" to config.buildEndpointUrl(),
                        "proxy" to proxy.toString(),
                        "message" to (e.message ?: "Unknown error")
                    ))
                    Result.error(NymError.NetworkError.Generic(e.message ?: "Unknown network error"))
                }
            }
        }
    }

    /**
     * 同步版本的请求方法（用于测试连接等场景）
     */
    fun sendRequestSync(request: OpenAIRequest): Result<OpenAIResponse> {
        return try {
            configureProxyAuthenticator()

            val clientBuilder = okhttp3.OkHttpClient.Builder()
                .connectTimeout(config.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(config.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .writeTimeout(config.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

            if (proxy != Proxy.NO_PROXY) {
                clientBuilder.proxy(proxy)
            }

            if (!config.verifySSL) {
                clientBuilder.sslSocketFactory(
                    UnsafeSslSocketFactory.getUnsafeSSLContext().socketFactory,
                    UnsafeSslSocketFactory.getUnsafeTrustManager()
                )
                clientBuilder.hostnameVerifier { _, _ -> true }
            }

            val client = clientBuilder.build()
            val jsonRequest = json.encodeToString(OpenAIRequest.serializer(), request)
            val requestBody = jsonRequest.toRequestBody("application/json".toMediaType())

            val requestBuilder = okhttp3.Request.Builder()
                .url(config.buildEndpointUrl())
                .post(requestBody)

            config.getAuthHeaders().forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }

            val httpRequest = requestBuilder.build()

            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    return Result.error(
                        NymError.APIError.ServerError("HTTP ${response.code}: $errorBody")
                    )
                }

                val responseBody = response.body?.string() ?: ""
                val openAIResponse = json.decodeFromString(OpenAIResponse.serializer(), responseBody)
                Result.success(openAIResponse)
            }
        } catch (e: Exception) {
            Result.error(NymError.NetworkError.Generic(e.message ?: "Network error"))
        }
    }
}

/**
 * 不安全的SSL配置类，用于测试环境或需要绕过SSL验证的场景
 * 注意：在生产环境中请勿使用
 */
private object UnsafeSslSocketFactory {
    private val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
        object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }
    )

    fun getUnsafeSSLContext(): javax.net.ssl.SSLContext {
        val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        return sslContext
    }

    fun getUnsafeTrustManager(): javax.net.ssl.X509TrustManager {
        return trustAllCerts[0] as javax.net.ssl.X509TrustManager
    }
}