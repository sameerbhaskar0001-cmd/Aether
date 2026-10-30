package com.example.ai.network

import com.example.util.ApiKeyStorage
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    const val BASE_URL = "https://generativelanguage.googleapis.com/"

    /**
     * Rewrites outgoing Gemini requests to:
     * 1. Route through a secure backend proxy when `ApiKeyStorage.getGeminiProxyBaseUrl()` is configured,
     *    stripping any sentinel/client API key from the URL and attaching `Authorization: Bearer <token>` if present.
     * 2. Move the `key` query parameter into the `x-goog-api-key` HTTP header when calling Google directly,
     *    preventing API keys from appearing in request URLs or proxy access logs.
     */
    internal fun rewriteRequestForSecurityAndProxy(
        original: Request,
        proxyBaseUrl: String = ApiKeyStorage.getGeminiProxyBaseUrl(),
        proxyAuthToken: String = ApiKeyStorage.getProxyAuthToken()
    ): Request {
        val originalUrl = original.url
        val queryKey = originalUrl.queryParameter("key")
        val builder = original.newBuilder()

        val parsedProxyUrl = proxyBaseUrl.trim().takeIf { it.isNotBlank() }?.toHttpUrlOrNull()
        if (parsedProxyUrl != null) {
            val proxyPathPrefix = parsedProxyUrl.encodedPath.trimEnd('/')
            val combinedPath = if (proxyPathPrefix.isEmpty()) {
                originalUrl.encodedPath
            } else {
                "$proxyPathPrefix${originalUrl.encodedPath}"
            }
            val newUrl = originalUrl.newBuilder()
                .scheme(parsedProxyUrl.scheme)
                .host(parsedProxyUrl.host)
                .port(parsedProxyUrl.port)
                .encodedPath(combinedPath)
                .removeAllQueryParameters("key")
                .build()
            builder.url(newUrl)

            if (proxyAuthToken.isNotBlank()) {
                builder.header("Authorization", "Bearer $proxyAuthToken")
            }
            if (!queryKey.isNullOrBlank() && queryKey != ApiKeyStorage.PROXY_SENTINEL_KEY) {
                builder.header("x-goog-api-key", queryKey)
            }
        } else if (!queryKey.isNullOrBlank()) {
            // Strip key from URL query string and pass via x-goog-api-key header instead
            val sanitizedUrl = originalUrl.newBuilder()
                .removeAllQueryParameters("key")
                .build()
            builder.url(sanitizedUrl)
            if (queryKey != ApiKeyStorage.PROXY_SENTINEL_KEY) {
                builder.header("x-goog-api-key", queryKey)
            }
        }

        return builder.build()
    }

    private val securityAndProxyInterceptor = Interceptor { chain ->
        val rewritten = rewriteRequestForSecurityAndProxy(chain.request())
        chain.proceed(rewritten)
    }

    private val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor { message ->
            val sanitized = com.example.util.PrivacyUtil.sanitizeLog(message)
            android.util.Log.d("OkHttp", sanitized)
        }.apply {
            level = if (com.example.BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("x-goog-api-key")
            redactHeader("Authorization")
        }
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(securityAndProxyInterceptor)
            .addInterceptor(logging)
            .build()
    }

    val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    private val retrofitInstance: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    val service: GeminiApiService by lazy {
        retrofitInstance.create(GeminiApiService::class.java)
    }

    val dynamicService: GeminiDynamicApiService by lazy {
        retrofitInstance.create(GeminiDynamicApiService::class.java)
    }
}
