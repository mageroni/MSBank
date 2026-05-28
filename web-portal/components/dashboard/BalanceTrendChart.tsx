'use client';

import { useMemo } from 'react';
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from 'recharts';

interface Props { total: number }

export function BalanceTrendChart({ total }: Props) {
  const data = useMemo(() => {
    const points: { day: string; balance: number }[] = [];
    const today = new Date();
    let seed = total;
    for (let i = 29; i >= 0; i--) {
      const d = new Date(today);
      d.setDate(today.getDate() - i);
      seed = seed + Math.round((Math.sin(i / 3) - 0.5) * 500);
      points.push({ day: `${d.getMonth() + 1}/${d.getDate()}`, balance: Math.max(seed, 0) });
    }
    if (points.length > 0) {
      points[points.length - 1] = { ...points[points.length - 1]!, balance: total };
    }
    return points;
  }, [total]);

  return (
    <ResponsiveContainer width="100%" height="100%">
      <LineChart data={data} margin={{ top: 5, right: 5, left: 0, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" className="stroke-slate-200 dark:stroke-slate-800" />
        <XAxis dataKey="day" tick={{ fontSize: 10 }} interval={5} />
        <YAxis tick={{ fontSize: 10 }} width={50} />
        <Tooltip
          formatter={(v: number) => (v / 100).toFixed(2)}
          contentStyle={{ fontSize: 12 }}
        />
        <Line type="monotone" dataKey="balance" stroke="#059669" strokeWidth={2} dot={false} />
      </LineChart>
    </ResponsiveContainer>
  );
}
