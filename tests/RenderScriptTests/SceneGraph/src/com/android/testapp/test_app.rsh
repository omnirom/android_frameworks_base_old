// Copyright (C) 2012 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma version(1)

#pragma rs java_package_name(com.android.testapp)

// Helpers
typedef struct ViewProjParams {
    rs_matrix4x4 viewProj;
} VSParams;

typedef struct ModelParams {
    rs_matrix4x4 model;
} VObjectParams;

typedef struct CameraParams {
    float4 cameraPos;
} FShaderParams;

typedef struct LightParams {
    float4 lightPos_0;
    float4 lightColor_0;
    float4 lightPos_1;
    float4 lightColor_1;
    float4 cameraPos;
    float4 diffuse;
} FShaderLightParams;

typedef struct BlurOffsets {
    float blurOffset0;
    float blurOffset1;
    float blurOffset2;
    float blurOffset3;
} FBlurOffsets;

typedef struct VertexShaderInputs {
    float4 position;
    float3 normal;
    float2 texture0;
} VShaderInputs;
