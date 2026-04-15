import * as THREE from 'three';

export class Renderer {
  constructor() {
    this.scene = new THREE.Scene();

    this.camera = new THREE.PerspectiveCamera(
      75,
      window.innerWidth / window.innerHeight,
      0.1,
      1000
    );

    this.camera.position.set(0, 1, 5);

    this.renderer = new THREE.WebGLRenderer({ antialias: true });
    this.renderer.setSize(window.innerWidth, window.innerHeight);
    document.body.appendChild(this.renderer.domElement);

    // 🔥 Persistent buffer (KEY FIX)
    this.maxPoints = 10000;

    const positions = new Float32Array(this.maxPoints * 3);

    this.geometry = new THREE.BufferGeometry();
    this.geometry.setAttribute(
      'position',
      new THREE.BufferAttribute(positions, 3)
    );

    this.material = new THREE.LineBasicMaterial({
      color: 0x00ffcc
    });

    this.traceLine = new THREE.Line(this.geometry, this.material);
    this.scene.add(this.traceLine);

    this.pointIndex = 0;

    this.needsUpdate = false;

    window.addEventListener('resize', () => this.onResize());
  }

  /**
   * Stream points into GPU buffer
   */
  addPoint(p) {
    const pos = this.geometry.attributes.position.array;

    const i = this.pointIndex * 3;

    pos[i] = p.x;
    pos[i + 1] = p.y;
    pos[i + 2] = p.z;

    this.pointIndex++;

    if (this.pointIndex >= this.maxPoints) {
      this.pointIndex = 0;
    }

    this.geometry.attributes.position.needsUpdate = true;

    this.needsUpdate = true;
  }

  /**
   * Optional batch update (fallback mode)
   */
  updatePoints(points) {
    const pos = this.geometry.attributes.position.array;

    for (let i = 0; i < points.length && i < this.maxPoints; i++) {
      const p = points[i];
      const idx = i * 3;

      pos[idx] = p.x;
      pos[idx + 1] = p.y;
      pos[idx + 2] = p.z;
    }

    this.pointIndex = points.length;
    this.geometry.attributes.position.needsUpdate = true;

    this.needsUpdate = true;
  }

  /**
   * Render loop (now conditional-friendly)
   */
  render() {
    requestAnimationFrame(() => this.render());

    this.renderer.render(this.scene, this.camera);
  }
addEddy(point) {
  const geo = new THREE.SphereGeometry(0.05);
  const mat = new THREE.MeshBasicMaterial({ color: 0xff3300 });

  const mesh = new THREE.Mesh(geo, mat);
  mesh.position.set(point.x, point.y, point.z);

  this.scene.add(mesh);
}

  addVector(v) {
    const points = [
      new THREE.Vector3(0, 0, 0),
      new THREE.Vector3(v.x, v.y, v.z)
    ];

    const geometry = new THREE.BufferGeometry().setFromPoints(points);
    const material = new THREE.LineBasicMaterial({ color: 0xffffff });
    const line = new THREE.Line(geometry, material);

    this.scene.add(line);
  }
