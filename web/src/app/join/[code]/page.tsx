"use client";

import React, { useEffect, useState } from "react";
import { useParams, useSearchParams } from "next/navigation";

interface TrackPreview {
  videoId: string;
  title: string;
  artist: string;
  thumbnailUrl?: string;
  durationMs?: number;
}

interface PartyPreviewData {
  code: string;
  hostName?: string;
  membersCount: number;
  nowPlaying?: TrackPreview;
}

export default function JoinPartyPage() {
  const params = useParams();
  const searchParams = useSearchParams();
  const rawCode = (params?.code as string) || searchParams.get("code") || "";
  const code = rawCode.replace(/[^A-Za-z0-9]/g, "").toUpperCase().slice(0, 6);
  const server = searchParams.get("server") || "https://yz-music-party.onrender.com";

  const [preview, setPreview] = useState<PartyPreviewData | null>(null);
  const [loading, setLoading] = useState(true);

  const schemeUrl = `yzmusic://party/${code}${server ? `?server=${encodeURIComponent(server)}` : ""}`;

  useEffect(() => {
    if (!code || code.length !== 6) {
      setLoading(false);
      return;
    }

    // Try automatic redirect on mobile
    if (typeof window !== "undefined") {
      const isMobile = /Android|iPhone|iPad|iPod/i.test(navigator.userAgent);
      if (isMobile) {
        window.location.href = schemeUrl;
      }
    }

    const base = server.replace(/\/+$/, "");
    fetch(`${base}/api/parties/${code}/preview`)
      .then((res) => (res.ok ? res.json() : null))
      .then((data) => {
        if (data) setPreview(data);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [code, server, schemeUrl]);

  return (
    <main className="min-h-screen bg-[#0b0f19] text-slate-100 flex items-center justify-center p-6 bg-[radial-gradient(circle_at_50%_0%,rgba(99,102,241,0.25),transparent_70%)]">
      <div className="max-w-md w-full bg-[#161e31]/80 backdrop-blur-xl border border-white/10 rounded-3xl p-8 text-center shadow-2xl">
        <div className="inline-flex items-center gap-2 px-3.5 py-1.5 rounded-full text-xs font-semibold bg-indigo-500/15 text-indigo-400 border border-indigo-500/30 mb-6">
          <span className="w-2 h-2 rounded-full bg-emerald-500 shadow-[0_0_8px_#22c55e]" />
          <span>
            {preview?.membersCount ? `${preview.membersCount} Listeners In Party` : "Live Listening Party"}
          </span>
        </div>

        <h1 className="text-3xl font-bold tracking-tight mb-2">Play Together</h1>
        <p className="text-sm text-slate-400 mb-6 leading-relaxed">
          {preview?.hostName
            ? `Hosted by ${preview.hostName}. Join now to sync playback in real time.`
            : "Listen to real-time synchronized music with your friends on YZ Music."}
        </p>

        <div className="bg-black/35 border border-dashed border-white/10 rounded-2xl p-4 mb-6 flex flex-col items-center gap-1">
          <span className="text-xs uppercase tracking-wider text-slate-400">Party Code</span>
          <span className="text-3xl font-extrabold tracking-widest text-indigo-300 font-mono">
            {code || "------"}
          </span>
        </div>

        {preview?.nowPlaying && preview.nowPlaying.title && (
          <div className="flex items-center gap-3.5 bg-white/[0.04] border border-white/10 rounded-xl p-3 mb-6 text-left">
            {preview.nowPlaying.thumbnailUrl ? (
              <img
                src={preview.nowPlaying.thumbnailUrl}
                alt={preview.nowPlaying.title}
                className="w-12 h-12 rounded-lg object-cover bg-slate-800"
              />
            ) : (
              <div className="w-12 h-12 rounded-lg bg-slate-800 flex items-center justify-center text-slate-500 font-bold">
                ♪
              </div>
            )}
            <div className="overflow-hidden">
              <div className="font-semibold text-sm truncate">{preview.nowPlaying.title}</div>
              <div className="text-xs text-slate-400 truncate">{preview.nowPlaying.artist || "Unknown Artist"}</div>
            </div>
          </div>
        )}

        <div className="flex flex-col gap-3">
          <a
            href={schemeUrl}
            className="w-full py-3.5 px-6 rounded-xl font-semibold bg-indigo-600 hover:bg-indigo-500 text-white shadow-lg shadow-indigo-600/30 transition-all hover:-translate-y-0.5"
          >
            Open in YZ Music
          </a>
          <a
            href="https://github.com/ashil/YZ-Music/releases"
            target="_blank"
            rel="noopener noreferrer"
            className="w-full py-3.5 px-6 rounded-xl font-semibold bg-white/5 hover:bg-white/10 text-slate-200 border border-white/10 transition-all"
          >
            Download YZ Music
          </a>
        </div>
      </div>
    </main>
  );
}
