// Copyright (C) 2003-2024, Foxit Software Inc..
// All Rights Reserved.
//
// http://www.foxitsoftware.com
//
// The following code is copyrighted and contains proprietary information and trade secrets of Foxit Software Inc..
// You cannot distribute any part of Foxit Cloud API to any third party or general public,
// unless there is a separate license agreement with Foxit Software Inc. which explicitly grants you such rights.
//
// This file contains an example to demonstrate how to use Foxit Cloud API to extract image from pdf files.

package com.foxit.simple_demo.extract_image_from_pdf;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;


import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class RestException extends Exception{
    public Response response;

    public RestException(Response r, String message){ 
        super(message);
        response = r;
    }
}

public class Extract_image_from_pdf {

    private String client_id = "";
    private String secret_id = "";
    // The signature of parameters will be calculated in the actual interface call in combination with the secret Id.
    private String sn = "testsn";
    // TODO: replace with your own input doc path and output file path
    private static final String input_file_path = "./input_files/PDF2Img.pdf";
    private static final String output_file_path = "output_files/extract_image_from_pdf/Image.zip";
    
    // TODO: replace with server base url
    private static final String base_url = "https://servicesapi.foxitsoftware.cn/api";

    private static final OkHttpClient client = new OkHttpClient().newBuilder()
                                                    .connectTimeout(60, TimeUnit.SECONDS)
                                                    .writeTimeout(60, TimeUnit.SECONDS)
                                                    .readTimeout(60, TimeUnit.SECONDS)
                                                    .build();

    private void getCredentialsParams (String credentials_path) throws Exception {
        // Read clientId and secretId form the json file.
        try(FileReader reader = new FileReader(credentials_path)) {
            JsonParser parser = new JsonParser();
            JsonObject object = (JsonObject) parser.parse(reader);
            JsonObject object_client = object.get("client_credentials").getAsJsonObject();
            client_id = object_client.get("client_id").getAsString();
            secret_id = object_client.get("secret_id").getAsString();
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private static String generateMD5(String input) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(input.getBytes());
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private HttpUrl.Builder buildURI(String endpoint) throws Exception {
        return HttpUrl.parse(base_url).newBuilder().addPathSegments(endpoint);
    }

    private String extractPDFTask(String input_file_path, String mode, String page_range) throws Exception {
        Map<String, String> query_params = new TreeMap<>();
        query_params.put("clientId", client_id);
        query_params.put("mode", mode);
        query_params.put("pageRange", page_range);

        String query_string = query_params.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + encode(entry.getValue()))
            .collect(Collectors.joining("&"));

        query_string += "&sk=" + secret_id;

        sn = generateMD5(query_string);

        HttpUrl url = buildURI("document/extract")
            .addQueryParameter("sn", sn)
            .addQueryParameter("clientId", client_id)
            .build();

        String file_name = (new File(input_file_path)).getName();
        RequestBody body = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("inputDocument", file_name, RequestBody
            .create(new File(input_file_path), MediaType.parse("text/plain")))
            .addFormDataPart("mode", mode)
            .addFormDataPart("pageRange", page_range)
			.build();
 
        Request request = new Request.Builder()
            .url(url)
            .method("POST", body)
            .build();
        // Upload a file and create a new workflow task.
        Response response = client.newCall(request).execute();
        if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
        String jsonData = response.body().string();

        JsonParser parser=new JsonParser();
        JsonObject object=(JsonObject) parser.parse(jsonData);
        if(object.get("code").getAsInt() == 0) {
            JsonObject object_data = object.get("data").getAsJsonObject();
            JsonObject object_task_info = object_data.get("taskInfo").getAsJsonObject();
            return object_task_info.get("taskId").getAsString();
        } else {
            throw new IOException("http response error:" + response);
        }        
    }

    private String getTaskInfo(String task_id) throws Exception {
        Map<String, String> query_params = new TreeMap<>();
        query_params.put("clientId", client_id);
        query_params.put("taskId", task_id);

        String query_string = query_params.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + encode(entry.getValue()))
            .collect(Collectors.joining("&"));

        query_string += "&sk=" + secret_id;
        sn = generateMD5(query_string);

        HttpUrl url = buildURI("task")
            .addQueryParameter("sn", sn)
            .addQueryParameter("clientId", client_id)
            .addQueryParameter("taskId", task_id)
            .build();
        Request request = new Request.Builder()
            .url(url)
            .build();
        
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new RestException(response, "Unexpected code " + response);
            String jsonData = response.body().string();

            JsonParser parser = new JsonParser();
            JsonObject object = (JsonObject) parser.parse(jsonData);
            if(object.get("code").getAsInt() == 0) {
                JsonObject object_data = object.get("data").getAsJsonObject();
                int percentage = object_data.get("taskInfo").getAsJsonObject().get("percentage").getAsInt();
                String task_info = object_data.get("taskInfo").toString();
                return task_info;
            } else {
                throw new RestException(response, "Unexpected code " + response);
            }
        }       
    }

    private String pollForDocId(String task_id, int interval_in_miliseconds) throws Exception {
        JsonParser parser = new JsonParser();
        do{
            try {
                String task_info = getTaskInfo(task_id);
                JsonObject object = (JsonObject) parser.parse(task_info);
                if(object.get("percentage").getAsInt() == 100){
                    System.out.println("Task completed.");
                    return object.get("docId").getAsString();
                }
            } catch (RestException e) {
                String jsonData = e.response.body().string();
                JsonObject object = (JsonObject) parser.parse(jsonData);
                JsonObject object_data = object.get("data").getAsJsonObject();
                String detail = object_data.get("detail").getAsString();
                // when task is running, the task api will return error
                // if task is running, try to get taskInfo later.
                if(detail.indexOf("The task is running") > -1) {
                    System.out.printf("Task is running, retry in %d miliseconds", interval_in_miliseconds);
                } else {
                    throw e;
                }
            }
            Thread.sleep(interval_in_miliseconds);
        }while(true);        
    }

    private void downLoadFileByDocId(String doc_id, String output_file_path) throws Exception {
        String file_name = (new File(output_file_path)).getName();

        Map<String, String> query_params = new TreeMap<>();
        query_params.put("clientId", client_id);
        query_params.put("docId", doc_id);
        query_params.put("fileName", file_name);

        String query_string = query_params.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + encode(entry.getValue()))
            .collect(Collectors.joining("&"));

        query_string += "&sk=" + secret_id;
        sn = generateMD5(query_string);
        HttpUrl url = buildURI("download")
            .addQueryParameter("sn", sn)
            .addQueryParameter("clientId", client_id)
            .addQueryParameter("docId", doc_id)
            .addQueryParameter("fileName", file_name)
            .build();
        Request request = new Request.Builder()
            .url(url)
            .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
            FileOutputStream file_output = new FileOutputStream(output_file_path);
            file_output.write(response.body().bytes());
            file_output.close();
            System.out.println("Download stream finished.");
        }            
    }

    public static void start() throws Exception {
        File file = new File(output_file_path);           
        File dir = new File(file.getParent());
        if(!dir.exists() || !dir.isDirectory()) {
            dir.mkdirs();
        }                
  
        Extract_image_from_pdf extract_image_from_pdf = new Extract_image_from_pdf();
        extract_image_from_pdf.getCredentialsParams("foxit_cloud_api_credentials.json");
        String task_id = extract_image_from_pdf.extractPDFTask(input_file_path , "extractImages", "all");
        String doc_id = extract_image_from_pdf.pollForDocId(task_id, 2000);
        extract_image_from_pdf.downLoadFileByDocId(doc_id, output_file_path);
        System.out.println("Extract image from PDF successfully!");
    }

    public static void main (String[] args) {
        try {
            Extract_image_from_pdf.start();
        } catch (RestException e) {
            System.out.println(e.getMessage());
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }
}