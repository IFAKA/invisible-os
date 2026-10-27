"use client";
import { Canvas, useFrame } from "@react-three/fiber";
import { Float } from "@react-three/drei";
import { useRef } from "react";
import * as THREE from "three";
import { pulse } from "@/lib/pulse";

const RINGS = 3;

function Button() {
  const group = useRef<THREE.Group>(null!);
  const rings = useRef<(THREE.Mesh | null)[]>([]);
  useFrame((state, dt) => {
    const wide = state.size.width > 900;
    const y = typeof window === "undefined" ? 0 : window.scrollY / window.innerHeight;
    // The button drifts to the right of the page on a wide screen, to the middle on a phone, and sinks as you scroll.
    group.current.position.x = THREE.MathUtils.damp(group.current.position.x, wide ? 2.4 - Math.min(y, 3) * 0.6 : 0, 3, dt);
    group.current.position.y = THREE.MathUtils.damp(group.current.position.y, wide ? 0 : -1.9 - y * 0.8, 3, dt);
    group.current.scale.setScalar(THREE.MathUtils.damp(group.current.scale.x, wide ? 0.78 : 0.6, 3, dt));
    group.current.rotation.y += dt * 0.15;
    const now = performance.now() / 1000;
    rings.current.forEach((m, i) => {
      if (!m) return;
      const t = now - pulse.at - i * 0.18;
      const k = THREE.MathUtils.clamp(t / 1.8, 0, 1);
      const e = 1 - Math.pow(1 - k, 3);
      m.scale.setScalar(1 + e * 5);
      (m.material as THREE.MeshBasicMaterial).opacity = t < 0 || k >= 1 ? 0 : (1 - k) * 0.5;
    });
  });
  return (
    <group ref={group}>
      <Float speed={1.2} rotationIntensity={0.15} floatIntensity={0.6}>
        <mesh>
          <sphereGeometry args={[0.9, 64, 64]} />
          <meshStandardMaterial color="#d8d8d3" roughness={0.45} metalness={0.1} emissive="#f4f4f1" emissiveIntensity={0.02} />
        </mesh>
        {Array.from({ length: RINGS }, (_, i) => (
          <mesh key={i} ref={(el) => { rings.current[i] = el; }}>
            <torusGeometry args={[1, 0.006, 8, 128]} />
            <meshBasicMaterial color="#f4f4f1" transparent opacity={0} />
          </mesh>
        ))}
      </Float>
    </group>
  );
}

export default function Scene() {
  return (
    <Canvas dpr={[1, 1.75]} camera={{ position: [0, 0, 6], fov: 40 }} gl={{ antialias: true, alpha: true }}>
      <ambientLight intensity={0.35} />
      <directionalLight position={[3, 4, 5]} intensity={2.2} />
      <Button />
    </Canvas>
  );
}
