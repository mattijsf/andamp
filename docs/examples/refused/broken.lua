-- Broken on purpose: no metadata block.
--
-- Adding this one should be refused with a reason, and nothing should be
-- stored.

function build(g, ctx)
  return { [0] = g.input(0), [1] = g.input(1) }
end
