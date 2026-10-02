-- Hostile on purpose: loops forever while it is being loaded.
--
-- The host abandons the worker after its deadline, so this should be refused
-- and the app should not freeze.

plugin {
  id      = "com.example.runaway",
  name    = "Runaway",
  version = "1.0.0",
  author  = "Andamp examples",
  about   = "Never finishes loading, on purpose.",
}

while true do end

function build(g, ctx)
  return { [0] = g.input(0), [1] = g.input(1) }
end
