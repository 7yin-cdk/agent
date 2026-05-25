package com.library.agent.tool;

import cn.hutool.http.HttpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class WeatherTool {

    /**
     * 和风天气 KEY
     */
    private static final String API_KEY = "b4ff1d1f2e074c319d64343e95f659fa";

    /**
     * GeoAPI
     */
    private static final String GEO_API =
            "https://nk3aarapnr.re.qweatherapi.com/geo/v2/city/lookup";

    /**
     * 实时天气API
     */
    private static final String WEATHER_API =
            "https://nk3aarapnr.re.qweatherapi.com//v7/weather/now";

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    /**
     * 查询天气
     */
    @Tool("根据城市名称查询当前天气")
    public String getWeather(String city) {

        try {

            // 1. 查询城市ID
            String locationId = getLocationId(city);

            if (locationId == null) {
                return "未找到城市：" + city;
            }

            // 2. 查询天气
            String weatherUrl = WEATHER_API
                    + "?location=" + locationId
                    + "&key=" + API_KEY;

            String weatherResp = HttpUtil.get(weatherUrl);

            JsonNode weatherJson =
                    objectMapper.readTree(weatherResp);

            JsonNode now =
                    weatherJson.get("now");

            if (now == null) {
                return "天气查询失败";
            }

            String temp = now.get("temp").asText();
            String text = now.get("text").asText();
            String humidity = now.get("humidity").asText();
            String windDir = now.get("windDir").asText();
            String windScale = now.get("windScale").asText();

            return """
                    城市：%s
                    天气：%s
                    温度：%s℃
                    湿度：%s%%
                    风向：%s
                    风力：%s级
                    """
                    .formatted(
                            city,
                            text,
                            temp,
                            humidity,
                            windDir,
                            windScale
                    );

        } catch (Exception e) {

            System.out.println("天气查询失败");

            return "天气查询失败：" + e.getMessage();
        }
    }

    /**
     * 查询城市LocationID
     */
    private String getLocationId(String city)
            throws Exception {

        String encodedCity =
                URLEncoder.encode(
                        city,
                        StandardCharsets.UTF_8
                );

        String geoUrl = GEO_API
                + "?location=" + encodedCity
                + "&key=" + API_KEY;

        String geoResp = HttpUtil.get(geoUrl);

        JsonNode geoJson =
                objectMapper.readTree(geoResp);

        JsonNode locations =
                geoJson.get("location");

        if (locations == null || locations.isEmpty()) {
            return null;
        }

        return locations.get(0)
                .get("id")
                .asText();
    }
}
