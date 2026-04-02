local Gradient = {}

Gradient.RGB = function(text, ...) --Expects colors as {R, G, B}
    local colors = {...}
    local builder = Chat:createTextBuilder()
    local len = text:len()
    
    if #colors < 2 then Chat:log("Requires at least 2 colors") end
    
    for i = 1, len do
        local t = (i - 1) / (len - 1)
        local segmentCount = #colors - 1
        local segmentIndex = math.min(math.floor(t * segmentCount) + 1, segmentCount)
        local segmentT = (t * segmentCount) - (segmentIndex - 1)
        
        local c1 = colors[segmentIndex]
        local c2 = colors[segmentIndex + 1]
        
        local r = math.floor(c1[1] + (c2[1] - c1[1]) * segmentT)
        local g = math.floor(c1[2] + (c2[2] - c1[2]) * segmentT)
        local b = math.floor(c1[3] + (c2[3] - c1[3]) * segmentT)
        
        builder:append(text:sub(i, i)):withColor(r, g, b)
    end
    
    return builder:build()
end

Gradient.Hex = function(text, ...)

    local function hexToRGB(hex)
        return math.floor(hex / 65536) % 256, math.floor(hex / 256) % 256, hex % 256
    end

    local hexColors = {...}
    local rgbColors = {}
    
    for i, hex in ipairs(hexColors) do
        local r, g, b = hexToRGB(hex)
        rgbColors[i] = {r, g, b}
    end
  
    return Gradient.RGB(text, table.unpack(rgbColors))
end

return Gradient

-- Example Usage:
    --Chat:log(Gradient.Hex("Hello World!", 0xD40EC6, 0xC50C2A, 0xD827B4))
    --Chat:log(Gradient.RGB("Hello World", {255, 0, 0}, {0, 255, 0}, {0, 0, 255}))