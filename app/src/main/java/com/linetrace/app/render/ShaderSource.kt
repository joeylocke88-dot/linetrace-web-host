package com.linetrace.app.render

object ShaderSource {
    const val VERTEX_SHADER = """
        uniform mat4 uMvpMatrix; 
        uniform vec3 uOffset;
        uniform float uDepthBias; 
        uniform float uTime;
        attribute vec4 aPosition; 
        varying float vDepth;
        varying vec2 vUv;
        varying float vQuality;
        varying float vTime;
        void main() { 
            float quality = aPosition.w;
            vec3 posOffset = aPosition.xyz + uOffset;
            if (quality < -0.5) {
                float jitter = sin(aPosition.x * 100.0 + uTime * 30.0) * 0.05;
                posOffset += vec3(jitter, jitter, jitter);
            }
            vec4 pos = uMvpMatrix * vec4(posOffset, 1.0); 
            pos.z += uDepthBias * pos.w; 
            gl_Position = pos; 
            vDepth = gl_Position.z / gl_Position.w;
            vUv = (gl_Position.xy / gl_Position.w) * 0.5 + 0.5;
            vQuality = quality;
            vTime = uTime;
            gl_PointSize = 10.0; 
        }
    """

    const val FRAGMENT_SHADER = """
        precision mediump float; 
        uniform vec4 uColor; 
        uniform sampler2D uCameraDepth;
        uniform vec2 uScreenSize;
        uniform float uTime;
        varying float vDepth;
        varying vec2 vUv;
        varying float vQuality;
        varying float vTime;
        float getDepth(vec2 uv) {
            vec2 packedDepth = texture2D(uCameraDepth, uv).rg;
            return (packedDepth.r * 255.0 + packedDepth.g * 255.0 * 256.0) / 1000.0;
        }
        uniform mat3 uDepthUvMatrix;
        void main() { 
            vec2 depthUv = (uDepthUvMatrix * vec3(vUv, 1.0)).xy;
            float realDepth = getDepth(depthUv);
            vec3 finalRgb;
            float finalAlpha = uColor.a;
            if (vQuality < -0.5) {
                float flicker = step(0.5, fract(vDepth * 10.0 + vTime * 20.0)); 
                float pulse = 0.8 + 0.2 * sin(vTime * 30.0);
                finalRgb = vec3(1.0, 0.0, 0.0) * pulse;
                finalAlpha = 1.0 * flicker;
            } else {
                vec3 lowQualityColor = vec3(1.0, 0.5, 0.0);
                vec3 highQualityColor = uColor.rgb;        
                finalRgb = mix(lowQualityColor, highQualityColor, clamp(vQuality, 0.0, 1.0));
            }
            if (vDepth > realDepth + 0.05) {
                if (vQuality < -0.5) {
                    gl_FragColor = vec4(1.0, 0.0, 0.0, finalAlpha * 0.3);
                } else {
                    gl_FragColor = vec4(finalRgb * 0.2, finalAlpha * 0.2); 
                }
            } else {
                gl_FragColor = vec4(finalRgb, finalAlpha);
            }
        }
    """

    const val BACKGROUND_VERTEX_SHADER = "attribute vec4 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord; void main() { gl_Position = aPosition; vTexCoord = aTexCoord; }"
    
    const val BACKGROUND_FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vTexCoord; uniform samplerExternalOES sTexture; void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }"

    const val RIBBON_VERTEX_SHADER = """
        uniform mat4 uMvpMatrix;
        uniform vec3 uOffset;
        uniform float uDepthBias;
        uniform float uWallHeight;
        attribute vec4 aPosition;
        attribute float aStability;
        varying float vDepth;
        varying vec2 vUv;
        varying float vHeightFactor;
        varying float vStability;
        uniform float uTime;
        void main() {
            vec3 pos = aPosition.xyz + uOffset;
            float stability = aStability;
            if (stability < -0.5) {
                pos += sin(pos.x * 50.0 + uTime * 30.0) * 0.05;
            }
            pos.y += aPosition.w * uWallHeight;
            vec4 clipPos = uMvpMatrix * vec4(pos, 1.0);
            clipPos.z += uDepthBias * clipPos.w;
            gl_Position = clipPos;
            vDepth = gl_Position.z / gl_Position.w;
            vUv = gl_Position.xy * 0.5 + 0.5;
            vHeightFactor = aPosition.w; 
            vStability = stability;
            if (uTime < 0.0) vStability = -1.0; // Dummy check
        }
    """

    const val RIBBON_FRAGMENT_SHADER = """
        precision mediump float;
        uniform vec4 uColor;
        uniform sampler2D uCameraDepth;
        uniform vec2 uScreenSize;
        varying float vDepth;
        varying vec2 vUv;
        varying float vHeightFactor;
        varying float vStability;
        uniform float uTime;
        uniform float uThermalTemp;
        float getDepth(vec2 uv) {
            vec2 packedDepth = texture2D(uCameraDepth, uv).rg;
            return (packedDepth.r * 255.0 + packedDepth.g * 255.0 * 256.0) / 1000.0;
        }
        uniform mat3 uDepthUvMatrix;
        void main() {
            vec2 depthUv = (uDepthUvMatrix * vec3(vUv, 1.0)).xy;
            float realDepth = getDepth(depthUv);
            vec3 color = uColor.rgb;
            float alpha = uColor.a;
            
            // Thermal Warning Effect: Red pulse if > 42C
            if (uThermalTemp > 42.0) {
                float pulse = sin(uTime * 10.0) * 0.5 + 0.5;
                color = mix(color, vec3(1.0, 0.2, 0.0), pulse * 0.3);
            }

            if (vStability < -0.5) {
                float flicker = step(0.5, fract(vDepth * 10.0 + uTime * 20.0));
                color = vec3(1.0, 0.0, 0.0);
                alpha *= flicker * 2.0;
            } else {
                alpha *= (1.0 - vHeightFactor * 0.7);
                float scanline = sin(vHeightFactor * 30.0 - vDepth * 5.0) * 0.1;
                color += scanline;
            }
            if (vDepth > realDepth + 0.05) {
                gl_FragColor = vec4(color * 0.2, alpha * 0.3);
            } else {
                gl_FragColor = vec4(color, alpha);
            }
        }
    """

    const val DIAGNOSTIC_VERTEX_SHADER = """#version 310 es
        in vec4 aPosition;
        out vec2 vUv;
        void main() {
            gl_Position = aPosition;
            vUv = aPosition.xy * 0.5 + 0.5;
        }
    """

    const val DIAGNOSTIC_FRAGMENT_SHADER = """#version 310 es
        ... (existing code)
    """

    const val LINE_CRAWLER_VERTEX_SHADER = """#version 310 es
        layout(location = 0) in vec4 aPosition;
        out vec2 vUv;
        void main() {
            gl_Position = aPosition;
            vUv = aPosition.xy * 0.5 + 0.5;
        }
    """

    const val LINE_CRAWLER_FRAGMENT_SHADER = """#version 310 es
        precision highp float;
        uniform sampler2D uCameraDepth;
        uniform mat4 uInvVpMatrix;
        uniform mat3 uDepthUvMatrix;
        uniform vec2 uZParams;
        uniform float uTime;
        uniform float uThermalTemp;
        uniform int uSurfelCount;
        uniform int uPathPointCount;
        uniform vec3 uWorldMin;
        uniform vec3 uStabilizedOrigin;
        uniform float uCellSize;
        in vec2 vUv;
        out vec4 outColor;

        struct Surfel {
            vec4 posRadius;   
            vec4 normalConf;  
            vec4 color;       
            uvec2 id;         
            uvec2 timestamp;  
        };

        struct PathPoint {
            vec4 posStability;
        };

        layout(std430, binding = 0) readonly buffer SurfelBuffer {
            Surfel surfels[];
        };

        layout(std430, binding = 1) readonly buffer GridBuffer {
            uint gridOffsets[];
        };

        layout(std430, binding = 2) readonly buffer PathBuffer {
            PathPoint pathPoints[];
        };

        float getDepth(vec2 uv) {
            vec2 packedDepth = texture(uCameraDepth, uv).rg;
            return (packedDepth.r * 255.0 + packedDepth.g * 255.0 * 256.0) / 1000.0;
        }

        vec3 worldFromDepth(vec2 uv, float d) {
            float clipZ = (uZParams.x * (-d) + uZParams.y) / d;
            vec4 clipPos = vec4(uv * 2.0 - 1.0, clipZ, 1.0);
            vec4 worldPos = uInvVpMatrix * clipPos;
            return worldPos.xyz / worldPos.w;
        }

        uint expandBits(uint v) {
            v = (v * 0x00010001u) & 0xFF0000FFu;
            v = (v * 0x00000101u) & 0x0F00F00Fu;
            v = (v * 0x00000011u) & 0xC30C30C3u;
            v = (v * 0x00000005u) & 0x49249249u;
            return v;
        }

        uint morton3(vec3 p) {
            uvec3 ip = uvec3(clamp(p, 0.0, 1023.0));
            return expandBits(ip.x) | (expandBits(ip.y) << 1) | (expandBits(ip.z) << 2);
        }

        void main() {
            vec2 depthUv = (uDepthUvMatrix * vec3(vUv, 1.0)).xy;
            float d = getDepth(depthUv);
            if (d <= 0.0) discard;

            vec3 worldPos = worldFromDepth(vUv, d);
            vec3 camPos = (uInvVpMatrix * vec4(0.0, 0.0, 0.0, 1.0)).xyz;
            vec3 rayDir = normalize(worldPos - camPos);
            float maxT = length(worldPos - camPos);
            
            float accum = 0.0;
            
            // NOVEL OPTIMIZATION: Thermal-Aware Segmented Marching
            // We skip the "Dead Zone" between the camera-vicinity and the surface.
            // This reduces heat by focusing compute only where data exists.
            
            float ribFreq = 25.0; 
            float ribSpeed = 12.0;

            // Interleaved Gradient Noise for temporal jittering
            float jitter = fract(sin(dot(vUv * fract(uTime), vec2(12.9898, 78.233))) * 43758.5453);
            
            // Adaptive Step Count
            int stepsS1 = 8;
            int stepsS2 = 16;
            
            // Segment 1: Camera Vicinity (Path Trail)
            float t1_start = 0.05;
            float t1_end = min(maxT, 1.2);
            float dt1 = (t1_end - t1_start) / float(stepsS1);
            
            for(int i = 0; i < stepsS1; i++) {
                float t = t1_start + dt1 * (float(i) + jitter);
                if (t >= t1_end) break;
                vec3 p = camPos + rayDir * t;

                // Optimization: Limit path search to last 32 points
                int startK = max(0, uPathPointCount - 32);
                for(int k = startK; k < uPathPointCount; k++) {
                    vec3 pp = pathPoints[k].posStability.xyz;
                    float distSq = dot(p - pp, p - pp);
                    accum += exp(-distSq * 60.0) * 0.15;
                }
            }

            // Segment 2: Surface Vicinity (Surfels)
            float t2_start = max(t1_end, maxT - 0.5);
            float t2_end = maxT;
            float dt2 = (t2_end - t2_start) / float(stepsS2);

            for(int i = 0; i < stepsS2; i++) {
                float t = t2_start + dt2 * (float(i) + jitter);
                if (t >= t2_end) break;
                vec3 p = camPos + rayDir * t;

                vec3 gridPos = floor((p - uWorldMin) / uCellSize);
                uint key = morton3(gridPos);
                uint startIdx = gridOffsets[key];
                uint endIdx = (key < 1048575u) ? gridOffsets[key + 1u] : uint(uSurfelCount);
                
                for(uint j = startIdx; j < endIdx; j++) {
                    if (j >= uint(uSurfelCount)) break;
                    Surfel s = surfels[j];
                    vec3 diff = p - s.posRadius.xyz;
                    float distSq = dot(diff, diff);
                    float g = exp(-distSq / (s.posRadius.w * s.posRadius.w * 0.5));
                    
                    if (abs(s.normalConf.y) < 0.3) {
                        float dy = p.y - s.posRadius.y;
                        float ribs = step(0.7, sin(dy * ribFreq - uTime * ribSpeed));
                        accum += exp(-abs(dy) * 2.5) * exp(-length(diff.xz) * 12.0) * ribs * 0.4;
                    }
                    accum += g * 0.12;
                }
            }

            accum = clamp(accum, 0.0, 1.0);
            vec3 scanColor = vec3(0.1, 0.9, 1.0);
            vec3 coreColor = vec3(1.0, 1.0, 1.0);
            vec3 finalRgb = mix(scanColor, coreColor, pow(accum, 2.5));
            float crawl = smoothstep(0.8, 1.0, sin((worldPos.y - uStabilizedOrigin.y) * 15.0 - uTime * 8.0));
            outColor = vec4((finalRgb + crawl * 0.2) * accum, accum * 0.75);
        }
    """
}
