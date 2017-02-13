package com.arialyy.aria.core.upload;

import android.util.Log;
import com.arialyy.aria.util.CheckUtil;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Set;
import java.util.UUID;

/**
 * Created by Aria.Lao on 2017/2/9.
 * 上传工具
 */
public class UploadUtil implements Runnable {
  private static final String TAG = "UploadUtil";
  private final String BOUNDARY = UUID.randomUUID().toString(); // 边界标识 随机生成
  private final String PREFIX = "--", LINE_END = "\r\n";
  private UploadEntity mUploadEntity;
  private UploadTaskEntity mTaskEntity;
  private IUploadListener mListener;
  private PrintWriter mWriter;
  private OutputStream mOutputStream;
  private HttpURLConnection mHttpConn;
  private long mCurrentLocation = 0;

  public UploadUtil(UploadTaskEntity taskEntity, IUploadListener listener) {
    mTaskEntity = taskEntity;
    CheckUtil.checkUploadEntity(taskEntity.uploadEntity);
    mUploadEntity = taskEntity.uploadEntity;
    if (listener == null) {
      throw new IllegalArgumentException("上传监听不能为空");
    }
    mListener = listener;
  }

  public void start() {
    new Thread(this).start();
  }

  @Override public void run() {
    File uploadFile = new File(mUploadEntity.getFilePath());
    if (!uploadFile.exists()) {
      Log.e(TAG, "【" + mUploadEntity.getFilePath() + "】，文件不存在。");
      mListener.onFail();
      return;
    }

    mListener.onPre();

    URL url = null;
    try {
      url = new URL(mTaskEntity.uploadUrl);
      mHttpConn = (HttpURLConnection) url.openConnection();
      mHttpConn.setUseCaches(false);
      mHttpConn.setDoOutput(true);
      mHttpConn.setDoInput(true);
      mHttpConn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
      mHttpConn.setRequestProperty("User-Agent", "CodeJava Agent");
      mHttpConn.setRequestProperty("Range", "bytes=" + 0 + "-" + "100");
      //内部缓冲区---分段上传防止oom
      mHttpConn.setChunkedStreamingMode(1024);

      //添加Http请求头部
      Set<String> keys = mTaskEntity.headers.keySet();
      for (String key : keys) {
        mHttpConn.setRequestProperty(key, mTaskEntity.headers.get(key));
      }

      mOutputStream = mHttpConn.getOutputStream();
      mWriter = new PrintWriter(new OutputStreamWriter(mOutputStream, mTaskEntity.charset), true);

      //添加文件上传表单字段
      keys = mTaskEntity.formFields.keySet();
      for (String key : keys) {
        addFormField(key, mTaskEntity.formFields.get(key));
      }
      mListener.onStart(uploadFile.length());
      addFilePart(mTaskEntity.attachment, uploadFile);
      Log.d(TAG, finish() + "");
    } catch (MalformedURLException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * 添加文件上传表单字段
   */
  private void addFormField(String name, String value) {
    mWriter.append(PREFIX).append(BOUNDARY).append(LINE_END);
    mWriter.append("Content-Disposition: form-data; name=\"")
        .append(name)
        .append("\"")
        .append(LINE_END);
    mWriter.append("Content-Type: text/plain; charset=")
        .append(mTaskEntity.charset)
        .append(LINE_END);
    mWriter.append(LINE_END);
    mWriter.append(value).append(LINE_END);
    mWriter.flush();
  }

  /**
   * 上传文件
   *
   * @param fieldName 文件上传attachment
   * @throws IOException
   */
  private void addFilePart(String fieldName, File uploadFile) throws IOException {
    String fileName = uploadFile.getName();
    mWriter.append(PREFIX).append(BOUNDARY).append(LINE_END);
    mWriter.append("Content-Disposition: form-data; name=\"")
        .append(fieldName)
        .append("\"; filename=\"")
        .append(fileName)
        .append("\"")
        .append(LINE_END);
    mWriter.append("Content-Type: ")
        .append(URLConnection.guessContentTypeFromName(fileName))
        .append(LINE_END);
    mWriter.append("Content-Transfer-Encoding: binary").append(LINE_END);
    mWriter.append(LINE_END);
    mWriter.flush();

    FileInputStream inputStream = new FileInputStream(uploadFile);
    byte[] buffer = new byte[1024];
    int bytesRead = -1;
    while ((bytesRead = inputStream.read(buffer)) != -1) {
      mCurrentLocation += bytesRead;
      mOutputStream.write(buffer, 0, bytesRead);
      mListener.onProgress(mCurrentLocation);
    }
    mOutputStream.flush();
    inputStream.close();
    mListener.onComplete();
    mWriter.append(LINE_END);
    mWriter.flush();
  }

  /**
   * 任务结束操作
   *
   * @throws IOException
   */
  private String finish() throws IOException {
    StringBuilder response = new StringBuilder();

    mWriter.append(LINE_END).flush();
    mWriter.append(PREFIX).append(BOUNDARY).append(PREFIX).append(LINE_END);
    mWriter.close();

    int status = mHttpConn.getResponseCode();
    if (status == HttpURLConnection.HTTP_OK) {
      BufferedReader reader = new BufferedReader(new InputStreamReader(mHttpConn.getInputStream()));
      String line = null;
      while ((line = reader.readLine()) != null) {
        response.append(line);
      }
      reader.close();
      mHttpConn.disconnect();
    } else {
      throw new IOException("Server returned non-OK status: " + status);
    }

    return response.toString();
  }
}