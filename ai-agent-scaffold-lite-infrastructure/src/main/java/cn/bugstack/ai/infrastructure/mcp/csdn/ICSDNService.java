package cn.bugstack.ai.infrastructure.mcp.csdn;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.Headers;
import retrofit2.http.POST;

public interface ICSDNService {
//    @Headers({
//            "accept: application/json, text/plain, */*",
//            "content-type: application/json",
//            "origin: https://mp.csdn.net",
//            "referer: https://mp.csdn.net/mp_blog/creation/editor",
//            "x-ca-key: 203803574",
//            "x-ca-nonce: d29c029c-b89a-4a50-bfea-036c56beb3d8",
//            "x-ca-signature: pozYn0wL0iyUR/ja3ysrvuvWOnnXRIsf6VTLAFX2sO8=",
//            "x-ca-signature-headers: x-ca-key,x-ca-nonce",
//            "x-ca-stage: "
//    })
@Headers({
        "accept: */*",
        "content-type: application/json",
        "origin: https://editor.csdn.net",
        "referer: https://editor.csdn.net/",
        "user-agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "x-ca-key: 203803574",
        "x-ca-nonce: 111d9894-332b-4a70-bac4-9a1897771e61",
        "x-ca-signature: cTZARElSvhvcv5JpR63Pn5LDCXbb5cvTh/TSeDCDg4Y=",
        "x-ca-signature-headers: x-ca-key,x-ca-nonce"
})
    @POST("/blog-console-api/v3/mdeditor/saveArticle")
    Call<ArticleResponseDTO> saveArticle(@Body ArticleRequestDTO request, @Header("Cookie") String cookieValue);
}
