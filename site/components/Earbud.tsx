"use client";
import { Canvas, useFrame } from "@react-three/fiber";
import { ContactShadows, Environment, Lightformer } from "@react-three/drei";
import { useRef } from "react";
import * as THREE from "three";
import { pulse, story } from "@/lib/pulse";

const { damp, smoothstep: ss } = THREE.MathUtils;
const RINGS = 3;
const white = { color: "#f3f3f1", roughness: 0.3, clearcoat: 1, clearcoatRoughness: 0.08 } as const;

/** One earbud, lit like a studio shot. In "story" mode the page scroll turns it, lights its button and finally dims the room. */
function Bud({ mode }: { mode: "hero" | "story" }) {
  const root = useRef<THREE.Group>(null!);
  const btn = useRef<THREE.MeshStandardMaterial>(null!);
  const rings = useRef<(THREE.Mesh | null)[]>([]);
  useFrame(({ scene, clock }, dt) => {
    const p = mode === "story" ? story.p : 0.3;
    const t = clock.elapsedTime;
    const yaw = mode === "story" ? -0.9 * (1 - ss(p, 0, 0.3)) + Math.sin(p * 9) * 0.12 * ss(p, 0.3, 0.4) * (1 - ss(p, 0.7, 0.8)) + 0.9 * ss(p, 0.72, 1) : Math.sin(t * 0.4) * 0.35 - 0.2;
    root.current.rotation.y = damp(root.current.rotation.y, yaw, 4, dt);
    root.current.rotation.z = damp(root.current.rotation.z, mode === "story" ? 0.25 - p * 0.35 : 0.2, 4, dt);
    root.current.position.y = Math.sin(t * 0.9) * 0.04 - 0.1;
    const now = performance.now() / 1000;
    const flash = now > pulse.at ? Math.exp(-(now - pulse.at) * 3.5) : 0;
    const lit = mode === "story" ? ss(p, 0.2, 0.32) : 1;
    btn.current.emissiveIntensity = lit * (1.1 + flash * 3);
    // the room: dim at first, bright while it is explained, dark at the end (screen off)
    const room = mode === "story" ? 0.55 + 0.45 * ss(p, 0.05, 0.3) - 0.82 * ss(p, 0.72, 0.95) : 1;
    scene.environmentIntensity = damp(scene.environmentIntensity, room, 4, dt);
    rings.current.forEach((m, i) => {
      if (!m) return;
      const k = THREE.MathUtils.clamp((now - pulse.at - i * 0.16) / 1.6, 0, 1);
      m.scale.setScalar(0.9 + (1 - Math.pow(1 - k, 3)) * 3.2);
      (m.material as THREE.MeshBasicMaterial).opacity = now < pulse.at + i * 0.16 || k >= 1 ? 0 : (1 - k) * 0.55;
    });
  });
  return (
    <>
      <group ref={root}>
        <mesh position={[0, 0.5, 0]} scale={[1, 1.15, 0.95]}><sphereGeometry args={[0.43, 64, 64]} /><meshPhysicalMaterial {...white} /></mesh>
        <mesh position={[0.03, -0.32, 0]}><capsuleGeometry args={[0.15, 0.95, 16, 32]} /><meshPhysicalMaterial {...white} /></mesh>
        <mesh position={[0.03, -0.1, 0.146]} rotation={[Math.PI / 2, 0, 0]}><cylinderGeometry args={[0.07, 0.07, 0.03, 48]} /><meshStandardMaterial ref={btn} color="#0b1020" emissive="#4d8dff" emissiveIntensity={0} roughness={0.4} /></mesh>
        <mesh position={[0.03, -0.1, 0.15]}><torusGeometry args={[0.1, 0.01, 16, 64]} /><meshStandardMaterial color="#b9b9bf" metalness={0.8} roughness={0.25} /></mesh>
      </group>
      {Array.from({ length: RINGS }, (_, i) => (
        <mesh key={i} position={[0, 0.05, 0]} ref={(el) => { rings.current[i] = el; }}>
          <torusGeometry args={[1, 0.006, 8, 160]} /><meshBasicMaterial color="#7aa7ff" transparent opacity={0} />
        </mesh>
      ))}
    </>
  );
}

export default function Earbud({ mode }: { mode: "hero" | "story" }) {
  return (
    <Canvas dpr={[1, 2]} camera={{ position: [0, 0, 5.4], fov: 32 }} gl={{ antialias: true, alpha: true }}>
      <Environment resolution={256}>
        <Lightformer form="rect" intensity={4} position={[0, 4, 2]} scale={[8, 2, 1]} rotation-x={Math.PI / 2} />
        <Lightformer form="rect" intensity={2.6} color="#8fb4ff" position={[-5, 1, 1]} scale={[1.5, 6, 1]} rotation-y={Math.PI / 2} />
        <Lightformer form="rect" intensity={2} color="#ffd9b0" position={[5, 0, 2]} scale={[1.5, 6, 1]} rotation-y={-Math.PI / 2} />
        <Lightformer form="ring" intensity={2} position={[0, 0, -6]} scale={6} />
      </Environment>
      <Bud mode={mode} />
      <ContactShadows position={[0, -1.25, 0]} opacity={0.5} blur={2.6} scale={6} far={4} />
    </Canvas>
  );
}
