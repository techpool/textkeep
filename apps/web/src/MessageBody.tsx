import React from "react";
import { Typography, type TypographyProps } from "@mui/material";

type Props = Omit<TypographyProps, "children" | "dangerouslySetInnerHTML"> & {
  text?: string;
};

/** SMS is displayed as a React text child, without HTML or link interpretation. */
export function MessageBody({ text, ...props }: Props): React.ReactElement {
  return <Typography {...props}>{text}</Typography>;
}
