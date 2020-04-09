package com.zhoujiulong.baselib.http

import android.os.Handler
import android.os.Looper
import com.zhoujiulong.baselib.app.ActivityFragmentManager
import com.zhoujiulong.baselib.http.listener.DownLoadListener
import com.zhoujiulong.baselib.http.listener.OnTokenInvalidListener
import com.zhoujiulong.baselib.http.listener.RequestListener
import com.zhoujiulong.baselib.http.other.CodeConstant
import com.zhoujiulong.baselib.http.other.RequestErrorType
import com.zhoujiulong.baselib.http.response.BaseResponse
import com.zhoujiulong.baselib.utils.ContextUtil
import com.zhoujiulong.baselib.utils.NetworkUtil
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Author : zhoujiulong
 * Email : 754667445@qq.com
 * Time : 2017/03/24
 * Desc : 网络请求辅助类
 */
internal class RequestHelper private constructor() {
    private var mOnTokenInvalidListener: OnTokenInvalidListener? = null

    companion object {

        private var mInstance: RequestHelper? = null

        val instance: RequestHelper
            get() {
                if (mInstance == null) {
                    synchronized(RequestHelper::class.java) {
                        if (mInstance == null) {
                            mInstance = RequestHelper()
                        }
                    }
                }
                return mInstance!!
            }
    }

    /**
     * 设置 Token 失效回调
     */
    fun setOnTokenInvalidListener(onTokenInvalidListener: OnTokenInvalidListener) {
        mOnTokenInvalidListener = onTokenInvalidListener
    }

    /**
     * 发送请求
     *
     * @param reTag      请求标记，用于取消请求用
     * @param listener 请求完成后的回调
     * @param <T>      请求返回的数据对应的类型，第一层必须继承 BaseResponse
    </T> */
    fun <T> sendRequest(reTag: String, call: Call<T>, listener: RequestListener<T>) {
        if (!NetworkUtil.isNetworkAvailable(ContextUtil.getContext())) {
            listener.requestError(
                null,
                RequestErrorType.NO_INTERNET,
                "网络连接失败",
                CodeConstant.REQUEST_FAILD_CODE
            )
            return
        }
        RequestManager.instance.addCall(reTag, call)
        call.enqueue(object : Callback<T> {
            //异步请求
            override fun onResponse(call: Call<T>, response: Response<T>) {
                //判断请求是否取消了，如果取消了就不再往下执行
                if (!RequestManager.instance.hasRequest(reTag)) return
                RequestManager.instance.removeCall(reTag, call)
                val code = response.code()
                if (code != 200) {//接口请求失败
                    if (code == 502 || code == 404) {
                        listener.requestError(
                            null,
                            RequestErrorType.COMMON_ERROR,
                            "服务器异常，请稍后重试",
                            code
                        )
                    } else if (code == 504) {
                        listener.requestError(
                            null,
                            RequestErrorType.COMMON_ERROR,
                            "网络不给力,请检查网路",
                            code
                        )
                    } else {
                        listener.requestError(
                            null,
                            RequestErrorType.COMMON_ERROR,
                            "网络好像出问题了哦",
                            code
                        )
                    }
                } else if (response.body() == null) {//返回数据为空
                    listener.requestError(null, RequestErrorType.COMMON_ERROR, "返回数据为空！", code)
                } else {//接口请求成功
                    val body = response.body()
                    if (body is BaseResponse) {//判断返回的数据类型是否是继承 BaseResponse
                        val baseResponse = body as BaseResponse
                        if (CodeConstant.REQUEST_SUCCESS_CODE == baseResponse.code) {//获取数据正常
                            //再次判断请求所在的页面是否销毁了，如果销毁了不再往下执行
                            if (ActivityFragmentManager.getInstance().isReTagExist(reTag)) {
                                listener.requestSuccess(response.body() as T)
                            }
                            //{"message":"未登录或token失效","code":1002}
                        } else if (CodeConstant.ON_TOKEN_INVALID_CODE == baseResponse.code) {//Token失效
                            if (mOnTokenInvalidListener != null && !listener.checkLogin(
                                    baseResponse.code,
                                    baseResponse.message
                                )
                            ) {
                                listener.requestError(
                                    response.body(),
                                    RequestErrorType.TOKEN_INVALID,
                                    baseResponse.message,
                                    baseResponse.code
                                )
                                mOnTokenInvalidListener!!.onTokenInvalid(
                                    baseResponse.code,
                                    baseResponse.message
                                )
                            }
                        } else {//从后台获取数据失败，其它未定义的错误
                            listener.requestError(
                                response.body(),
                                RequestErrorType.COMMON_ERROR,
                                baseResponse.message,
                                baseResponse.code
                            )
                        }
                    } else {//Service类中的返回类型没有继承 BaseResponse
                        listener.requestError(
                            null,
                            RequestErrorType.COMMON_ERROR,
                            "请求返回数据的第一层类型必须继承 BaseResponse！",
                            CodeConstant.REQUEST_FAILD_CODE
                        )
                    }
                }
            }

            override fun onFailure(call: Call<T>, t: Throwable) {
                if (!RequestManager.instance.hasRequest(reTag)) return
                RequestManager.instance.removeCall(reTag, call)
                listener.requestError(
                    null,
                    RequestErrorType.COMMON_ERROR,
                    "请求失败",
                    CodeConstant.REQUEST_FAILD_CODE
                )
            }
        })
    }


    /**
     * 发送下载网络请求
     *
     * @param reTag              请求标记，用于取消请求用
     * @param downLoadFilePath 下载文件保存路径
     * @param downloadListener 下载回调
     */
    fun sendDownloadRequest(
        reTag: String, call: Call<ResponseBody>, downLoadFilePath: String,
        fileName: String, downloadListener: DownLoadListener
    ) {
        if (!NetworkUtil.isNetworkAvailable(ContextUtil.getContext())) {
            downloadListener.onFail("网络连接失败")
            return
        }
        RequestManager.instance.addCall(reTag, call)
        call.enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (!RequestManager.instance.hasRequest(reTag)) return
                RequestManager.instance.removeCall(reTag, call)
                if (response.code() != 200) {
                    if (response.code() == 502 || response.code() == 404) {
                        downloadListener.onFail(response.code().toString() + "服务器异常，请稍后重试")
                    } else if (response.code() == 504) {
                        downloadListener.onFail(response.code().toString() + "网络不给力,请检查网路")
                    } else {
                        downloadListener.onFail(response.code().toString() + "网络好像出问题了哦")
                    }
                    return
                }
                // 储存下载文件的目录
                val saveFile = File(downLoadFilePath)
                if (!saveFile.exists() || !saveFile.isDirectory) {
                    val mkDirSuccess = saveFile.mkdir()
                    if (!mkDirSuccess) downloadListener.onFail("创建本地的文件夹失败")
                    return
                }
                var file = File(saveFile, fileName)
                if (file.exists()) file = File(saveFile, "${System.currentTimeMillis()}${fileName}")
                val filePath = file.absolutePath
                //再次判断请求所在的页面是否销毁了，如果销毁了不再往下执行
                if (ActivityFragmentManager.getInstance().isReTagExist(reTag)) {
                    downloadListener.onStart()
                    val handler = Handler(Looper.getMainLooper())
                    Thread(Runnable {
                        var ips: InputStream? = null
                        var fos: FileOutputStream? = null
                        try {
                            ips = response.body()!!.byteStream()
                            val total = response.body()!!.contentLength()
                            fos = FileOutputStream(filePath)
                            var sum: Long = 0
                            val buf = ByteArray(2048)
                            var len: Int = ips!!.read(buf)
                            while (len != -1) {
                                fos.write(buf, 0, len)
                                sum += len.toLong()
                                val progress = (sum * 100 / total).toInt()
                                //再次判断请求所在的页面是否销毁了，如果销毁了不再往下执行
                                if (ActivityFragmentManager.getInstance().isReTagExist(reTag)) {
                                    handler.post { downloadListener.onProgress(progress) }
                                } else {
                                    break
                                }
                                len = ips.read(buf)
                            }
                            fos.flush()
                            //再次判断请求所在的页面是否销毁了，如果销毁了不再往下执行
                            if (ActivityFragmentManager.getInstance().isReTagExist(reTag)) {
                                handler.post { downloadListener.onDone(filePath) }
                            }
                        } catch (e: Exception) {
                            downLoadFileFail(reTag, e, downloadListener, handler)
                        } finally {
                            try {
                                ips?.close()
                            } catch (e: IOException) {
                                downLoadFileFail(reTag, e, downloadListener, handler)
                            }
                            try {
                                fos?.close()
                            } catch (e: IOException) {
                                downLoadFileFail(reTag, e, downloadListener, handler)
                            }
                        }
                    }).start()
                }
            }

            override fun onFailure(call: Call<ResponseBody>, throwable: Throwable) {
                if (!RequestManager.instance.hasRequest(reTag)) return
                RequestManager.instance.removeCall(reTag, call)
                downloadListener.onFail("下载失败" + throwable.message)
            }
        })
    }

    private fun downLoadFileFail(
        reTag: String,
        e: Exception,
        downloadListener: DownLoadListener,
        handler: Handler
    ) {
        //再次判断请求所在的页面是否销毁了，如果销毁了不再往下执行
        if (ActivityFragmentManager.getInstance().isReTagExist(reTag)) {
            handler.post { downloadListener.onFail("下载文件失败：" + e.message) }
        }
    }

}
















