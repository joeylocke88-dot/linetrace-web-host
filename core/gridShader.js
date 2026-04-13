import * as THREE from "https://cdn.jsdelivr.net/npm/three@0.160/build/three.module.js";

export class GridShader {
  constructor(scene) {

    this.uniforms = {
      time: { value: 0 },
      scale: { value: 12.0 },
      intensity: { value: 1.0 },
      distortion: { value: 0.0 }
    };

    this.material = new THREE.ShaderMaterial({
      uniforms: this.uniforms,
      vertexShader: `
        varying vec2 vUv;

        void main() {
          vUv = uv;
          gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
        }
      `,
      fragmentShader: `
        uniform float time;
        uniform float scale;
        uniform float intensity;
        uniform float distortion;

        varying vec2 vUv;

        float grid(vec2 uv, float size) {
          vec2 grid = fract(uv * size);
          float line = step(0.98, grid.x) + step(0.98, grid.y);
          return clamp(line, 0.0, 1.0);
        }

        void main() {

          vec2 uv = vUv - 0.5;

          // radial distortion (for IMU later)
          float d = length(uv);
          uv += normalize(uv) * sin(d * 10.0 - time) * distortion;

          float g = grid(uv + time * 0.02, scale);

          vec3 col = vec3(0.0, 1.0, 0.8) * g * intensity;

          // fade edges
          float fade = 1.0 - smoothstep(0.2, 0.8, d);

          gl_FragColor = vec4(col * fade, g * fade);
        }
      `,
      transparent: true
    });

    this.mesh = new THREE.Mesh(
      new THREE.PlaneGeometry(2, 2),
      this.material
    );

    this.mesh.frustumCulled = false;
    scene.add(this.mesh);
  }

  update(dt) {
    this.uniforms.time.value += dt;
  }

  setDistortion(value) {
    this.uniforms.distortion.value = value;
  }

  setIntensity(value) {
    this.uniforms.intensity.value = value;
  }
}
