import { jsPDF } from 'jspdf';

interface PDFSessionData {
  name: string;
  description: string;
  category?: string;
  topicCluster?: string;
  tags: string[];
  formatted_text?: string;
  raw_text?: string;
}

export function generateSessionPDF(session: PDFSessionData): void {
  const doc = new jsPDF({
    orientation: 'portrait',
    unit: 'mm',
    format: 'a4',
  });

  const pageWidth = doc.internal.pageSize.getWidth();
  const pageHeight = doc.internal.pageSize.getHeight();
  const margin = 20;
  const contentWidth = pageWidth - margin * 2;

  let y = margin;

  // Helper to add new page if content overflows
  const ensureSpace = (neededHeight: number) => {
    if (y + neededHeight > pageHeight - margin) {
      doc.addPage();
      y = margin;
      drawHeaderFooter();
    }
  };

  const drawHeaderFooter = () => {
    // Header
    doc.setFont('Helvetica', 'normal');
    doc.setFontSize(8);
    doc.setTextColor(115, 115, 115); // zinc-500
    doc.text('TERMINAL-U TRANSCRIBER // EXPORT PROTOCOL', margin, 12);
    doc.setDrawColor(228, 228, 231); // zinc-200
    doc.setLineWidth(0.2);
    doc.line(margin, 14, pageWidth - margin, 14);

    // Footer
    const pageNum = doc.getNumberOfPages();
    doc.text(`PAGE ${pageNum}`, pageWidth - margin, pageHeight - 12, { align: 'right' });
    doc.text('CLASSIFIED SYSTEM DATA', margin, pageHeight - 12);
    doc.line(margin, pageHeight - 14, pageWidth - margin, pageHeight - 14);
  };

  // Initialize first page header/footer
  drawHeaderFooter();
  y = 22;

  // Title
  doc.setFont('Helvetica', 'bold');
  doc.setFontSize(22);
  doc.setTextColor(9, 9, 11); // zinc-950

  const splitTitle = doc.splitTextToSize(session.name.toUpperCase(), contentWidth);
  ensureSpace(splitTitle.length * 8 + 5);
  doc.text(splitTitle, margin, y);
  y += splitTitle.length * 8 + 4;

  // Category and Metadata line
  ensureSpace(12);
  doc.setFillColor(6, 182, 212); // cyan-500
  doc.rect(margin, y, 4, 6, 'F');
  
  doc.setFont('Helvetica', 'bold');
  doc.setFontSize(10);
  doc.setTextColor(6, 182, 212); // cyan-500
  
  const categoryStr = (session.category || 'MEMO').toUpperCase();
  const topicStr = (session.topicCluster || 'GENERAL').toUpperCase();
  doc.text(`${categoryStr}  //  TOPIC CLUSTER: ${topicStr}`, margin + 6, y + 4.5);
  y += 12;

  // Description Block
  if (session.description) {
    const splitDesc = doc.splitTextToSize(session.description, contentWidth);
    ensureSpace(splitDesc.length * 5 + 8);
    
    doc.setFillColor(244, 244, 245); // zinc-100
    doc.rect(margin, y, contentWidth, splitDesc.length * 5 + 4, 'F');
    
    doc.setFont('Helvetica', 'normal');
    doc.setFontSize(10);
    doc.setTextColor(82, 82, 91); // zinc-600
    doc.text(splitDesc, margin + 4, y + 6);
    y += splitDesc.length * 5 + 10;
  }

  // Tags
  if (session.tags && session.tags.length > 0) {
    ensureSpace(8);
    doc.setFont('Helvetica', 'bold');
    doc.setFontSize(8);
    doc.setTextColor(115, 115, 115);
    doc.text('TAGS: ' + session.tags.map(t => `#${t.toUpperCase()}`).join('   '), margin, y);
    y += 10;
  }

  // Divider line
  ensureSpace(5);
  doc.setDrawColor(228, 228, 231);
  doc.setLineWidth(0.5);
  doc.line(margin, y, pageWidth - margin, y);
  y += 10;

  // Document Content
  const textToPrint = session.formatted_text || session.raw_text || '';
  
  // Basic markdown parser for PDF
  // We'll split the text by newlines and treat headers/tables uniquely
  const lines = textToPrint.split('\n');
  
  doc.setFont('Helvetica', 'normal');
  doc.setFontSize(10);
  doc.setTextColor(39, 39, 42); // zinc-800

  for (let i = 0; i < lines.length; i++) {
    let line = lines[i].trim();
    if (line === '') {
      y += 4;
      continue;
    }

    // Header 1 (e.g. # Header)
    if (line.startsWith('# ')) {
      const hText = line.replace('# ', '').toUpperCase();
      const splitH = doc.splitTextToSize(hText, contentWidth);
      ensureSpace(splitH.length * 6 + 10);
      
      y += 3;
      doc.setFont('Helvetica', 'bold');
      doc.setFontSize(14);
      doc.setTextColor(6, 182, 212); // cyan-500
      doc.text(splitH, margin, y);
      y += splitH.length * 6 + 4;
      continue;
    }

    // Header 2 (e.g. ## Header)
    if (line.startsWith('## ')) {
      const hText = line.replace('## ', '').toUpperCase();
      const splitH = doc.splitTextToSize(hText, contentWidth);
      ensureSpace(splitH.length * 5 + 8);
      
      y += 2;
      doc.setFont('Helvetica', 'bold');
      doc.setFontSize(11);
      doc.setTextColor(9, 9, 11);
      doc.text(splitH, margin, y);
      y += splitH.length * 5 + 3;
      continue;
    }

    // Blockquote
    if (line.startsWith('> ')) {
      const bText = line.replace('> ', '');
      const splitB = doc.splitTextToSize(bText, contentWidth - 8);
      ensureSpace(splitB.length * 5 + 6);
      
      doc.setFillColor(240, 253, 250); // cyan-50/50
      doc.rect(margin, y - 1, contentWidth, splitB.length * 5 + 3, 'F');
      
      doc.setDrawColor(6, 182, 212); // left border
      doc.setLineWidth(1);
      doc.line(margin, y - 1, margin, y - 1 + splitB.length * 5 + 3);

      doc.setFont('Helvetica', 'italic');
      doc.setFontSize(9.5);
      doc.setTextColor(21, 94, 117); // cyan-800
      doc.text(splitB, margin + 4, y + 4);
      y += splitB.length * 5 + 6;
      continue;
    }

    // Normal paragraph text
    doc.setFont('Helvetica', 'normal');
    doc.setFontSize(10);
    doc.setTextColor(39, 39, 42); // zinc-800

    // Handle bullet list item
    let isBullet = false;
    if (line.startsWith('* ') || line.startsWith('- ')) {
      line = '• ' + line.substring(2);
      isBullet = true;
    }

    const splitText = doc.splitTextToSize(line, contentWidth);
    ensureSpace(splitText.length * 5 + 2);
    
    doc.text(splitText, margin, y);
    y += splitText.length * 5 + 1.5;
  }

  // Save the PDF
  const filename = `${session.name.toLowerCase().replace(/[^a-z0-9]+/g, '_')}_protocol.pdf`;
  doc.save(filename);
}
