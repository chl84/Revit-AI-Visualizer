package com.revitvisualizer.app.openai

import org.json.JSONArray
import org.json.JSONObject

internal object OrientationFixtures {
    fun scene() = JSONObject("""{
        "orientation_status":"resolved",
        "north_interpretation":"North follows the long axis toward the left glazed gable.",
        "sun_side":"Low behind the right end of the building.",
        "shadow_direction":"Toward the north gable and left foreground along the ground plane.",
        "surfaces":[
          {"region":"left glazed gable","normal_azimuth_degrees":0,"normal_elevation_degrees":0,"occlusion":"clear"},
          {"region":"long timber facade","normal_azimuth_degrees":90,"normal_elevation_degrees":0,"occlusion":"unknown"},
          {"region":"south-facing roof plane","normal_azimuth_degrees":180,"normal_elevation_degrees":45,"occlusion":"clear"}],
        "notes":["Surface directions are inferred from perspective, not measured."]
    }""")

    fun response(scene: JSONObject = scene()): String = JSONObject().put("status", "completed").put("error", JSONObject.NULL)
        .put("output", JSONArray().put(JSONObject().put("type", "reasoning"))
            .put(JSONObject().put("type", "message").put("role", "assistant").put("status", "completed")
                .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", scene.toString()))))).toString()
}
